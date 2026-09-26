package spock.adb.timeline

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.device.ConnectedDevice
import spock.adb.device.ops.InspectionOperations
import spock.adb.logcat.LogcatEntry
import spock.adb.logcat.LogcatStream
import spock.adb.models.FragmentData
import spock.adb.pidsOf
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Records what one app does on one device, from the device's own log.
 *
 * A second logcat stream rather than the Logcat tab's: the tab streams only while it is live,
 * and the timeline has to have been recording before the bug to answer what came before it.
 * The stream is narrowed on the device — warnings and errors, plus the handful of lifecycle
 * tags [LogcatTimelineClassifier] reads — so it costs a fraction of a full tail.
 *
 * Lines are held until [DeviceClock] is measured, then released on the host's clock. If the
 * marker line never comes back — `log` missing, a device that drops it — the recorder falls back
 * to the moment each line arrived, which is late by the stream's latency but still in order.
 *
 * Fragments are not in the log. When one of the app's activities resumes, the recorder reads
 * the fragment tree once, shortly after, and records it when it changed.
 */
class DeviceEventRecorder(
    private val target: ConnectedDevice,
    private val packageName: String,
    private val sink: (TimelineEvent) -> Unit,
    /**
     * Called once, with why, if the stream ends on its own — `logd` restarting, the shell killed —
     * while the device may still be connected. The owner decides whether to record it and restart.
     */
    private val onEnded: (String) -> Unit = {},
    private val readFragments: (String) -> List<FragmentData> = { InspectionOperations(target.device).fragments(it) },
) {

    private val log = Logger.getInstance(DeviceEventRecorder::class.java)
    private val lock = Any()
    private val nonce = "spock-timeline-" + UUID.randomUUID().toString().take(NONCE_LENGTH)

    private var classifier: LogcatTimelineClassifier? = null
    private var stream: LogcatStream? = null
    private var tick: ScheduledFuture<*>? = null
    private var clock: DeviceClock? = null
    private var clockAbandoned = false
    private var startedAtMs = 0L
    private var syncWrittenAtMs: Long? = null
    private val waiting = ArrayList<Pair<LogcatEntry, Long>>()
    private var lastFragments: String? = null
    private var fragmentRead: ScheduledFuture<*>? = null
    private var replaySkipped = false
    private var droppedWaiting = 0

    @Volatile
    private var stopped = false

    /** Starts recording on a pooled thread. */
    fun start() {
        startedAtMs = System.currentTimeMillis()
        ApplicationManager.getApplication().executeOnPooledThread { begin() }
    }

    /**
     * Stops the stream and the timers. Under [lock], as [begin] sets them up under it: a stop that
     * lands mid-setup otherwise saw no stream yet, and the tail it missed ran for the life of the IDE.
     */
    fun stop() {
        synchronized(lock) {
            stopped = true
            stream?.stop()
            tick?.cancel(false)
            fragmentRead?.cancel(false)
            classifier?.flush()?.forEach(sink)
            classifier = null
        }
    }

    // A recorder that dies must say so in the timeline rather than go quiet: an empty timeline
    // reads as "nothing happened".
    @Suppress("TooGenericExceptionCaught")
    private fun begin() {
        val pids = try {
            target.device.pidsOf(packageName, PIDOF_TIMEOUT_SECONDS).mapNotNull { it.toIntOrNull() }.toSet()
        } catch (e: Exception) {
            // Treated as not running: the app's warnings then wait for its next process start to be
            // seen, while lifecycle events still match on the package name.
            log.info("Timeline could not read the pids of $packageName", e)
            emptySet()
        }
        val logcat = LogcatStream(
            target.device,
            onEntry = ::onEntry,
            onStopped = { failure ->
                val ended = synchronized(lock) {
                    if (stopped) return@synchronized false
                    stopped = true
                    tick?.cancel(false)
                    fragmentRead?.cancel(false)
                    classifier?.flush()?.forEach(sink)
                    classifier = null
                    true
                }
                if (ended) onEnded(failure?.message ?: "The device's log stream ended.")
            },
            command = COMMAND,
        )
        synchronized(lock) {
            if (stopped) return
            classifier = LogcatTimelineClassifier(packageName, pids, target.serialNumber)
            stream = logcat
            logcat.start()
            tick = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(::onTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
        writeSyncMarker()
    }

    /** Writes the line whose stamp measures the device clock. See [DeviceClock]. */
    @Suppress("TooGenericExceptionCaught")
    private fun writeSyncMarker() {
        try {
            // Let the stream attach first, or the marker is written before anyone is reading.
            Thread.sleep(ATTACH_DELAY_MS)
            if (stopped) return
            val before = System.currentTimeMillis()
            target.device.executeShellCommand(
                "log -t $SYNC_TAG -p i ${ShellQuote.quote(nonce)}",
                ShellOutputReceiver(),
                SYNC_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            val after = System.currentTimeMillis()
            synchronized(lock) { syncWrittenAtMs = (before + after) / 2 }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.info("Timeline could not write its clock marker; using arrival times", e)
            synchronized(lock) { abandonClock() }
        }
    }

    private fun onEntry(entry: LogcatEntry) {
        synchronized(lock) {
            if (entry.tag == SYNC_TAG) {
                if (entry.message.contains(nonce)) syncClock(entry)
                return
            }
            // `-T 1` replays one line from before the recorder started; it is not news. Dropped by
            // position rather than by time, so it goes even when the clock is never measured.
            if (!replaySkipped && entry.timestamp.isNotBlank()) {
                replaySkipped = true
                return
            }
            val arrived = System.currentTimeMillis()
            if (clock == null && !clockAbandoned) {
                if (waiting.size < MAX_WAITING) waiting += entry to arrived else droppedWaiting++
                return
            }
            deliver(entry, arrived)
        }
    }

    private fun syncClock(marker: LogcatEntry) {
        val writtenAt = syncWrittenAtMs ?: System.currentTimeMillis()
        clock = DeviceClock.sync(marker.timestamp, writtenAt)
        if (clock == null) abandonClock() else releaseWaiting()
    }

    private fun abandonClock() {
        clockAbandoned = true
        releaseWaiting()
    }

    private fun releaseWaiting() {
        val held = waiting.toList()
        waiting.clear()
        held.forEach { (entry, arrived) -> deliver(entry, arrived) }
        if (droppedWaiting > 0) {
            sink(
                TimelineEvent(
                    timeMs = System.currentTimeMillis(),
                    category = TimelineCategory.DEVICE,
                    severity = TimelineSeverity.WARNING,
                    title = "$droppedWaiting device log line(s) not recorded while measuring the device clock",
                    deviceSerial = target.serialNumber,
                ),
            )
            droppedWaiting = 0
        }
    }

    /** Called with [lock] held. */
    private fun deliver(entry: LogcatEntry, arrivedMs: Long) {
        val active = classifier ?: return
        val measured = clock?.toHostMillis(entry.timestamp, arrivedMs)
        active.accept(entry, measured ?: arrivedMs).forEach(::emit)
    }

    private fun onTick() {
        synchronized(lock) {
            if (clock == null && !clockAbandoned && System.currentTimeMillis() - startedAtMs > SYNC_GIVE_UP_MS) {
                log.info("Timeline clock marker never arrived from ${target.serialNumber}; using arrival times")
                abandonClock()
            }
            classifier?.flush()?.forEach(::emit)
        }
    }

    private fun emit(event: TimelineEvent) {
        sink(event)
        if (event.category == TimelineCategory.ACTIVITY && event.title.endsWith(" resumed")) scheduleFragmentRead()
    }

    private fun scheduleFragmentRead() {
        fragmentRead?.cancel(false)
        fragmentRead = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule(::recordFragments, FRAGMENT_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    // Best effort: a failed read loses one fragment snapshot, and says nothing wrong.
    @Suppress("TooGenericExceptionCaught")
    private fun recordFragments() {
        if (stopped) return
        val rows = try {
            readFragments(packageName).flatMap { it.flatten() }
        } catch (e: Exception) {
            log.info("Timeline could not read fragments of $packageName", e)
            return
        }
        val summary = rows.joinToString(" › ") { it.fragment.substringAfterLast('.') }
        // The read can take seconds; the developer may have moved to another app meanwhile.
        if (stopped || summary.isEmpty() || summary == lastFragments) return
        lastFragments = summary
        sink(
            TimelineEvent(
                timeMs = System.currentTimeMillis(),
                category = TimelineCategory.ACTIVITY,
                severity = TimelineSeverity.INFO,
                title = "Fragments: $summary",
                detail = rows.joinToString("\n") { "  ".repeat(it.depth) + it.fragment },
                deviceSerial = target.serialNumber,
            ),
        )
    }

    companion object {
        const val SYNC_TAG = "SpockTimeline"

        private const val NONCE_LENGTH = 8
        private const val PIDOF_TIMEOUT_SECONDS = 5L
        private const val SYNC_TIMEOUT_SECONDS = 5L
        private const val ATTACH_DELAY_MS = 500L
        private const val SYNC_GIVE_UP_MS = 5_000L
        private const val TICK_MS = 400L
        private const val FRAGMENT_DELAY_MS = 800L
        private const val MAX_WAITING = 2_000

        private val LIFECYCLE_TAGS = listOf("create", "start", "restart", "resume", "paused", "stop", "destroy")
            .flatMap { listOf("wm_on_${it}_called", "am_on_${it}_called") }

        /**
         * Warnings and up from everything, lifecycle at info. `*:W` is quoted: the device shell
         * would otherwise expand the star against its working directory.
         */
        val COMMAND = buildString {
            append("logcat -v threadtime -b main,system,crash,events -T 1 '*:W'")
            (listOf("ActivityManager", "am_crash", "am_anr", SYNC_TAG) + LIFECYCLE_TAGS)
                .forEach { append(' ').append(it).append(":I") }
        }
    }
}
