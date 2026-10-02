package spock.adb.timeline

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterEvent
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionChange
import spock.adb.flutter.FlutterSessionListener
import spock.adb.flutter.FlutterSessionService
import spock.adb.flutter.FlutterSessionServiceListener
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.SessionReads
import spock.adb.flutter.SessionState
import spock.adb.flutter.analysis.FlutterErrorReader
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.FlutterTimelineMapper
import spock.adb.flutter.analysis.FrameStats
import spock.adb.flutter.analysis.HttpProfileReader
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceException
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Puts the live Flutter session on the Debug Timeline (design §4): errors, routes, bursts of slow
 * frames and failed requests, on the host's clock, beside the log lines and actions around them.
 *
 * Follows [spock.adb.flutter.FlutterSessionService]: [sessionCreated] for each session as it is
 * created, so what DDS replays on connect is kept, and [sessionChanged] for which one is current. Events are
 * queued as they arrive and turned into rows on [tick] — every [TICK_MS] while a session is live —
 * once the session's [DeviceTime] is measured, so a row lands where it happened on the host's
 * clock, not where it arrived. Past [CLOCK_WAIT_MS] without a clock, rows are placed on the
 * device's clock and say so.
 *
 * Frames: rows only in profile builds, one per burst of slow frames in each [FRAME_WINDOW_MS]
 * window ([FlutterTimelineMapper.slowFrames]). Debug frames make none — every frame of a debug
 * build is slow, and the times mean nothing [FR4].
 *
 * HTTP: while the session records, the profile is read every [HTTP_POLL_MS] with `updatedSince`,
 * and each failed request is one row, by its id.
 *
 * And the session's own story: its start ("Flutter session: <app> on <device> — DDS, pid N"), its
 * end with why — including the engine destroyed by Back at the root activity, which leaves the
 * session connected with no UI isolate — and Spock turning HTTP recording on and back off.
 *
 * Rows come from the P5a mapper, which scrubs VM Service tokens and URL queries again (A8, A12).
 */
class FlutterTimelineRecorder(
    private val sink: (TimelineEvent) -> Unit,
    /** Starts [tick]ing every [TICK_MS]; cancelled when no session is live. */
    private val ticker: (Runnable) -> Future<*>,
    private val reads: Reads = Reads.SESSION,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether rows are wanted now ("Record device events"); while not, what arrives is dropped. */
    private val active: () -> Boolean = { true },
) : FlutterSessionServiceListener, Disposable {

    /** What the recorder asks the app. Blocking calls; on the ticking thread. */
    interface Reads {
        fun refreshRate(session: FlutterSession): Double?

        fun httpProfile(session: FlutterSession, updatedSinceUs: Long?): JsonObject?

        companion object {
            val SESSION = object : Reads {
                override fun refreshRate(session: FlutterSession): Double? =
                    SessionReads.refreshRate(session, READ_TIMEOUT_MS)

                override fun httpProfile(session: FlutterSession, updatedSinceUs: Long?): JsonObject =
                    SessionReads.httpProfile(session, updatedSinceUs, READ_TIMEOUT_MS)
            }
        }
    }

    /** The current session, and what has been written about it. */
    private class Followed(val session: FlutterSession, val identity: AppIdentity?, val queue: Queue) {
        val app: String get() = identity?.applicationId ?: "the Flutter app"
        val serial: String? get() = identity?.serial
        var uiIsolateId: String? = null
        var isolateExitAt: Long? = null
        var enabledBySpock = false
        var frames = mutableListOf<FlutterExtensionEvent>()
        var windowStartedAt = 0L
        var fps: Double? = null
        var fpsAsked = false
        var lastHttpPollAt = 0L
        var updatedSinceUs: Long? = null
        var clockNoted = false

        /** What was written about this app's process, across its sessions. */
        val processKey: String
            get() = "${identity?.serial}|${identity?.applicationId}|${identity?.pid ?: session.snapshot.vmPid}"
    }

    /** One session's events, bounded without counting the queue on every event. */
    private class Queue(val session: FlutterSession) {
        private val events = ConcurrentLinkedQueue<FlutterEvent>()
        private val size = AtomicInteger()

        fun offer(event: FlutterEvent) {
            if (size.incrementAndGet() > MAX_QUEUED) {
                size.decrementAndGet()
                return
            }
            events += event
        }

        fun poll(): FlutterEvent? = events.poll()?.also { size.decrementAndGet() }

        val count: Int get() = size.get()
    }

    private val lock = Any()
    private val opening = ArrayList<Queue>()

    /**
     * Rows already written per process — `serial|app|pid` to keys of `(kind, device time,
     * isolate)` events and `(isolate, request id)` requests — so a new session on the same process
     * (the selection moving away and back, another app's Diagnose in between) does not write
     * again what DDS replays or what the HTTP profile still holds.
     */
    private val written = object : LinkedHashMap<String, LinkedHashSet<String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>?) =
            size > MAX_PROCESSES
    }
    private var current: Followed? = null
    private var ticking: Future<*>? = null
    private var disposed = false

    /** Starts queueing [session]'s events, from before it connects. */
    override fun sessionCreated(session: FlutterSession) {
        val queue = Queue(session)
        synchronized(lock) {
            opening += queue
            while (opening.size > MAX_OPENING) opening.removeAt(0)
        }
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = queue.offer(event)
            },
        )
    }

    override fun sessionChanged(change: FlutterSessionChange) {
        synchronized(lock) {
            if (disposed) return
            when (change) {
                is FlutterSessionChange.Connected -> start(change.session, change.identity)
                is FlutterSessionChange.Replaced -> {
                    end(change.previous, ENDED_REPLACED, bySpock = true)
                    start(change.session, change.identity)
                }
                is FlutterSessionChange.Disconnected -> end(change.session, change.reason, bySpock = isSpocks(change))
            }
        }
    }

    // Any exception — the VM Service's are checked ones — would end the periodic task, and the
    // session would go quiet for good.
    @Suppress("TooGenericExceptionCaught")
    private fun safeTick() {
        try {
            tick()
        } catch (e: Exception) {
            log.warn("Recording the Flutter session on the Timeline failed: ${Redaction.scrub(e.message.orEmpty())}")
        }
    }

    /** Turns what arrived into rows, and looks at the session. On the ticking thread. */
    fun tick() {
        val followed = synchronized(lock) { current } ?: return
        if (!active()) {
            // Not recording: what arrived is not kept for later, and the app is not asked anything.
            generateSequence { followed.queue.poll() }.count()
            return
        }
        readRefreshRateOnce(followed)
        synchronized(lock) {
            if (current !== followed) return
            val placement = placementOrWait(followed) ?: return
            drain(followed, placement)
            checkIsolate(followed)
            checkRecording(followed)
        }
        pollHttp(followed)
    }

    override fun dispose() {
        synchronized(lock) {
            disposed = true
            current = null
            opening.clear()
            ticking?.cancel(false)
            ticking = null
        }
    }

    /** Events queued and not yet turned into rows; for tests. */
    internal val queued: Int get() = synchronized(lock) { current?.queue?.count ?: 0 }

    /** Under [lock]. */
    private fun start(session: FlutterSession, identity: AppIdentity?) {
        if (current?.session === session) return
        // Sessions created after this one — a connect that has started since — keep their queues.
        val index = opening.indexOfFirst { it.session === session }
        val queue = if (index >= 0) opening[index] else Queue(session)
        if (index >= 0) opening.subList(0, index + 1).clear()
        val followed = Followed(session, identity, queue).apply { windowStartedAt = clock() }
        current = followed
        val snapshot = session.snapshot
        val kind = if (snapshot.connectionKind == ConnectionKind.DDS) "DDS" else "direct VM, read-only"
        val pid = identity?.pid ?: snapshot.vmPid?.toLong()
        val verified = identity?.let { " (verified by ${it.verifiedBy.label})" }.orEmpty()
        row(
            followed,
            snapshot.connectedAtHostMs ?: clock(),
            TimelineSeverity.INFO,
            "Flutter session: ${followed.app} on ${followed.serial ?: "a device"} — $kind" +
                pid?.let { ", pid $it" }.orEmpty() + verified,
        )
        if (ticking == null) ticking = ticker(Runnable { safeTick() })
    }

    /** Under [lock]: what was still queued, then the end and why. */
    private fun end(session: FlutterSession, reason: String, bySpock: Boolean) {
        val followed = current?.takeIf { it.session === session } ?: return
        val placement = placement(followed)
        drain(followed, placement)
        flushFrames(followed, placement, force = true)
        row(
            followed,
            clock(),
            if (bySpock) TimelineSeverity.INFO else TimelineSeverity.WARNING,
            "Flutter session ended: ${followed.app} — ${endWords(reason)}",
        )
        if (followed.enabledBySpock) restoreRow(followed, bySpock)
        current = null
        ticking?.cancel(false)
        ticking = null
    }

    /** Under [lock]: whether Spock's switching HTTP recording back off is known to have worked. */
    private fun restoreRow(followed: Followed, bySpock: Boolean) {
        val restored = followed.session.httpRestored
        val app = followed.app
        val (severity, title) = when {
            !bySpock ->
                TimelineSeverity.INFO to "HTTP recording for $app was not switched back off: the connection ended first"
            restored == true -> TimelineSeverity.INFO to "Spock switched HTTP recording back off for $app"
            else ->
                TimelineSeverity.WARNING to
                    "Spock could not switch HTTP recording back off for $app: it stays on until the app restarts"
        }
        row(followed, clock(), severity, category = TimelineCategory.HTTP, title = title)
    }

    /** Under [lock]: rows for what arrived. */
    private fun drain(followed: Followed, placement: FlutterTimelineMapper.Placement) {
        val profile = followed.session.buildMode == FlutterBuild.PROFILE
        generateSequence { followed.queue.poll() }.forEach { event -> take(followed, event, placement, profile) }
        flushFrames(followed, placement, force = false)
    }

    /** Under [lock]: one event — a row, a frame for the window, or a note that the UI isolate exited. */
    private fun take(
        followed: Followed,
        event: FlutterEvent,
        placement: FlutterTimelineMapper.Placement,
        profile: Boolean,
    ) {
        if (event.kind == ISOLATE_EXIT && event.isolateId != null && event.isolateId == followed.uiIsolateId) {
            followed.isolateExitAt = clock()
        }
        val read = FlutterExtensionEvent.from(event) ?: return
        val rowed = read.kind == FlutterExtensionEvent.ERROR || read.kind == FlutterExtensionEvent.NAVIGATION
        if (rowed && !firstTime(followed, "${read.kind}|${read.timestampMs}|${read.isolateId}")) return
        when (read.kind) {
            FlutterExtensionEvent.ERROR -> sink(FlutterTimelineMapper.error(FlutterErrorReader.read(read), placement))
            FlutterExtensionEvent.NAVIGATION -> FlutterTimelineMapper.navigation(read, placement)?.let(sink)
            FlutterExtensionEvent.FRAME -> if (profile && !read.history) followed.frames += read
        }
    }

    /** Under [lock]: the window's slow-frame bursts, once it is [FRAME_WINDOW_MS] old. */
    private fun flushFrames(followed: Followed, placement: FlutterTimelineMapper.Placement, force: Boolean) {
        val now = clock()
        if (!force && now - followed.windowStartedAt < FRAME_WINDOW_MS) return
        val frames = FrameStats.read(followed.frames)
        followed.frames = mutableListOf()
        followed.windowStartedAt = now
        if (frames.isEmpty()) return
        FlutterTimelineMapper.slowFrames(frames, FrameStats.budgetMs(followed.fps), FlutterBuild.PROFILE, placement)
            .forEach(sink)
    }

    /**
     * Under [lock]. A UI isolate that exited and was not replaced within [ISOLATE_GRACE_MS] is
     * the engine going away — on Android, Back at the root activity destroys it while the
     * process, and the session, live on. One replaced by another is a hot restart.
     */
    private fun checkIsolate(followed: Followed) {
        val snapshot = followed.session.snapshot
        val exitedAt = followed.isolateExitAt
        if (exitedAt == null) {
            snapshot.uiIsolateId?.let { followed.uiIsolateId = it }
            return
        }
        if (clock() - exitedAt < ISOLATE_GRACE_MS) return
        followed.isolateExitAt = null
        val now = snapshot.uiIsolateId
        when {
            snapshot.state is SessionState.Disconnected -> Unit
            now == null -> row(
                followed,
                exitedAt,
                TimelineSeverity.WARNING,
                "Flutter session: ${followed.app}'s UI isolate exited — the Flutter engine was destroyed " +
                    "(on Android, Back at the root activity does this). Spock stays connected for the next one.",
            )
            now != followed.uiIsolateId -> row(
                followed,
                exitedAt,
                TimelineSeverity.INFO,
                "Flutter session: ${followed.app} has a new UI isolate (a hot restart)",
            )
        }
        followed.uiIsolateId = now
    }

    /** Under [lock]: one row when Spock turned HTTP recording on for this session. */
    private fun checkRecording(followed: Followed) {
        if (followed.enabledBySpock) return
        val title = when (followed.session.snapshot.httpRecording) {
            HttpRecording.EnabledBySpock ->
                "Spock turned on HTTP recording for ${followed.app} (restored when Spock disconnects)"
            HttpRecording.AdoptedBySpock ->
                "Spock's HTTP recording for ${followed.app} from the earlier session is still on; " +
                    "Spock will switch it off when it disconnects"
            else -> return
        }
        followed.enabledBySpock = true
        row(followed, clock(), TimelineSeverity.INFO, category = TimelineCategory.HTTP, title = title)
    }

    /** Off the lock: a VM call. Failed requests, one row per request id. */
    private fun pollHttp(followed: Followed) {
        val snapshot = followed.session.snapshot
        val recording = snapshot.httpRecording == HttpRecording.EnabledBySpock ||
            snapshot.httpRecording == HttpRecording.AdoptedBySpock ||
            snapshot.httpRecording == HttpRecording.AlreadyOn
        val due = clock() - followed.lastHttpPollAt >= HTTP_POLL_MS
        val live = snapshot.state is SessionState.Connected && snapshot.uiIsolateId != null
        if (!due || !live || !recording) return
        followed.lastHttpPollAt = clock()
        val profile = try {
            HttpProfileReader.read(reads.httpProfile(followed.session, followed.updatedSinceUs))
        } catch (e: VmServiceException) {
            log.info("Could not read the HTTP profile: ${Redaction.scrub(e.message.orEmpty())}")
            return
        }
        synchronized(lock) {
            if (current !== followed) return
            val placement = placement(followed)
            profile.timestampUs?.let { followed.updatedSinceUs = it }
            profile.requests.forEach { request ->
                val failure = FlutterTimelineMapper.httpFailure(request, placement) ?: return@forEach
                if (!firstTime(followed, "http|${request.isolateId}|${failure.requestId}")) return@forEach
                sink(failure.row)
            }
        }
    }

    /** Under [lock]: whether [key] is new for [followed]'s process, remembering it. */
    private fun firstTime(followed: Followed, key: String): Boolean {
        val keys = written.getOrPut(followed.processKey) { LinkedHashSet() }
        if (!keys.add(key)) return false
        if (keys.size > MAX_KEYS_PER_PROCESS) keys.remove(keys.first())
        return true
    }

    // A refresh rate that cannot be read is assumed: one failure — a timeout, a bug — must not
    // stop the frames.
    @Suppress("TooGenericExceptionCaught")
    private fun readRefreshRateOnce(followed: Followed) {
        if (followed.fpsAsked || followed.session.buildMode != FlutterBuild.PROFILE) return
        followed.fpsAsked = true
        followed.fps = try {
            reads.refreshRate(followed.session)
        } catch (e: VmServiceException) {
            log.info("Could not read the display refresh rate: ${Redaction.scrub(e.message.orEmpty())}")
            null
        } catch (e: RuntimeException) {
            log.info("Could not read the display refresh rate", e)
            null
        }
    }

    /**
     * Where rows go: the session's device clock once measured. Null while it may still come, so
     * the caller waits — up to [CLOCK_WAIT_MS], after which rows stay on the device's own clock.
     */
    private fun placementOrWait(followed: Followed): FlutterTimelineMapper.Placement? {
        val slot = followed.session.deviceTime
        val waited = clock() - (followed.session.snapshot.connectedAtHostMs ?: followed.windowStartedAt)
        if (slot.current == null && !slot.done && waited < CLOCK_WAIT_MS) return null
        if (slot.current == null && !followed.clockNoted) {
            followed.clockNoted = true
            row(
                followed,
                clock(),
                TimelineSeverity.INFO,
                "Device clock not measured: ${followed.app}'s Flutter rows are on the device's clock, in UTC",
            )
        }
        return placement(followed)
    }

    /** Where rows go now, with the clock as far as it is known. */
    private fun placement(followed: Followed): FlutterTimelineMapper.Placement =
        placementOf(followed.session.deviceTime.current, followed.serial)

    /** A row about the session itself, on the host's clock already. */
    private fun row(
        followed: Followed,
        timeMs: Long,
        severity: TimelineSeverity,
        title: String,
        category: TimelineCategory = TimelineCategory.APP_LIFECYCLE,
    ) {
        sink(
            TimelineEvent(
                timeMs = timeMs,
                category = category,
                severity = severity,
                title = Redaction.scrub(title).take(TITLE_LIMIT),
                deviceSerial = followed.serial,
            ),
        )
    }

    companion object {
        const val TICK_MS = 500L
        const val CLOCK_WAIT_MS = 5_000L
        const val FRAME_WINDOW_MS = 2_000L
        const val HTTP_POLL_MS = 5_000L
        const val ISOLATE_GRACE_MS = 1_500L

        private const val ISOLATE_EXIT = "IsolateExit"
        private const val READ_TIMEOUT_MS = 2_000L
        private const val MAX_OPENING = 4
        private const val MAX_QUEUED = 20_000
        private const val MAX_KEYS_PER_PROCESS = 5_000
        private const val MAX_PROCESSES = 8
        private const val TITLE_LIMIT = 200
        private const val ENDED_REPLACED = FlutterSessionService.CLOSED_FOR_NEW_CONNECTION
        private val SPOCKS_REASONS =
            setOf(FlutterSessionService.DISCONNECTED_BY_SPOCK, ENDED_REPLACED, FlutterSession.CLOSED_BY_SPOCK)
        private val log = Logger.getInstance(FlutterTimelineRecorder::class.java)

        /** The mapper's placement for [time]; without one, rows stay on the device's clock, in UTC. */
        fun placementOf(time: DeviceTime?, serial: String?): FlutterTimelineMapper.Placement =
            if (time == null) {
                FlutterTimelineMapper.Placement(ZoneOffset.UTC, serial)
            } else {
                FlutterTimelineMapper.Placement(time.zone, serial, toHostMs = time::epochToHost)
            }

        private fun isSpocks(change: FlutterSessionChange.Disconnected) = change.reason in SPOCKS_REASONS

        /** Why a session ended, in words. */
        internal fun endWords(reason: String): String = when (reason) {
            FlutterSessionService.DISCONNECTED_BY_SPOCK, FlutterSession.CLOSED_BY_SPOCK -> "Spock disconnected"
            ENDED_REPLACED -> "Spock connected again, to a newer session"
            else -> "the connection was lost (${Redaction.scrub(reason)}): the app stopped, or `flutter run` ended"
        }
    }
}
