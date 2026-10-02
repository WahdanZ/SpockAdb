package spock.adb.flutter

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import spock.adb.device.ConnectedDevice
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Decides **when** to attach to the selected Flutter app (design §2), so a developer never
 * pastes an address: on a selection change, when the selected app's process starts, and when
 * Diagnose asks. It only calls [FlutterSessionService.ensureSession]; which session that gives,
 * and how, is the service's business.
 *
 * Gates first, so nothing is attempted against an app that has no VM Service to find: the
 * selected app must be a Flutter app, by its APK, and not a release build.
 *
 * [FlutterAttachOutcome.NotReady] is retried with backoff — [BACKOFF_MS], then the last step
 * again, up to [MAX_ATTEMPTS] — on [scheduler]'s thread, never the EDT, until the outcome is
 * anything else or the selection or the app's pid changes. The service turns a process past its
 * startup window into a terminal outcome itself, so the loop ends there. A terminal outcome
 * (no DDS, nothing found) is remembered per (device, app, pids) and not asked again by the
 * automatic triggers until the app runs as another process; Diagnose always asks, as the
 * developer may have run `flutter attach` since.
 *
 * Thread-safe. Everything blocking runs on [scheduler] or the caller's pooled thread.
 */
class FlutterFollower(
    private val attach: Attach,
    /** Null for an app that is not Flutter. Blocking: adb, cached per install. */
    private val buildOf: (ConnectedDevice, String) -> FlutterBuild?,
    /** `pidof`; null when adb failed. Blocking. */
    private val pidsOf: (ConnectedDevice, String) -> Set<Long>?,
    /** The Settings switch, read at each attempt. */
    private val recordHttp: () -> Boolean,
    private val scheduler: Scheduler = Scheduler.daemon(),
    /** Runs [attachNow]'s call so its caller can stop waiting; a pooled thread in production. */
    private val background: (Runnable) -> Future<*> = scheduler::submit,
) : Disposable {

    /** [FlutterSessionService.ensureSession], as the follower calls it. */
    fun interface Attach {
        fun ensure(
            device: ConnectedDevice,
            applicationId: String,
            processStartedAt: Long?,
            build: FlutterBuild,
            recordHttp: Boolean,
        ): FlutterAttachOutcome
    }

    /** Where attempts run. */
    interface Scheduler {
        fun schedule(delayMs: Long, task: Runnable): Future<*>

        fun submit(task: Runnable): Future<*> = schedule(0, task)

        fun shutdown() = Unit

        companion object {
            /** One daemon thread: attempts for one project run one at a time. */
            fun daemon(): Scheduler {
                val executor = ScheduledThreadPoolExecutor(1) { runnable ->
                    Thread(runnable, "Spock Flutter follower").apply { isDaemon = true }
                }.apply {
                    setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
                    allowCoreThreadTimeOut(true)
                    removeOnCancelPolicy = true
                }
                return object : Scheduler {
                    override fun schedule(delayMs: Long, task: Runnable): Future<*> =
                        executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)

                    override fun shutdown() {
                        executor.shutdownNow()
                    }
                }
            }
        }
    }

    private data class Target(val device: ConnectedDevice, val applicationId: String) {
        val serial: String get() = device.serialNumber
    }

    private data class Key(val serial: String, val applicationId: String, val pids: Set<Long>)

    private val lock = Any()

    /** Bumped by every trigger and by [dispose]: an attempt of an older generation stops. */
    private var generation = 0L
    private var target: Target? = null
    private var processStartedAt: Long? = null
    private var pids: Set<Long>? = null
    private var attempt = 0
    private var pending: Future<*>? = null
    private var disposed = false

    private val terminal = object : LinkedHashMap<Key, FlutterAttachOutcome>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FlutterAttachOutcome>?) =
            size > MAX_REMEMBERED
    }

    /** What the last attempt for the selected app came to; null before one, or for a non-Flutter app. */
    @Volatile
    var lastOutcome: FlutterAttachOutcome? = null
        private set

    /**
     * The selection changed: drops any attempt for the old one and tries the new one at once.
     * The same device and app again changes nothing. Cheap: safe on the EDT.
     */
    fun follow(device: ConnectedDevice?, applicationId: String?) {
        val next = if (device != null && !applicationId.isNullOrBlank()) Target(device, applicationId) else null
        synchronized(lock) {
            val same = next?.serial == target?.serial && next?.applicationId == target?.applicationId
            if (disposed || same) {
                if (same && next != null) target = next
                return
            }
            restart(next, startedAt = null)
            lastOutcome = null
        }
    }

    /**
     * The selected app's process [pid] started at [hostMs] on the host's clock (the Timeline's
     * device recorder saw ActivityManager start it). Starts over at once, telling the service
     * when the process started so its startup window is right. Cheap: safe under the recorder's lock.
     */
    fun processStarted(serial: String, applicationId: String, pid: Long, hostMs: Long) {
        synchronized(lock) {
            val now = target ?: return
            if (disposed || now.serial != serial || now.applicationId != applicationId) return
            if (pids?.contains(pid) == true && pending != null) return
            restart(now, startedAt = hostMs)
        }
    }

    /**
     * Diagnose's trigger: attaches to [applicationId] on [device] now — whatever was remembered —
     * and waits at most [budgetMs] for the answer. Past it, the attach goes on, and the answer is
     * [FlutterAttachOutcome.NotReady]. A NotReady for the selected app keeps the backoff going,
     * so the next report finds the session. Blocking: call from a pooled thread.
     */
    fun attachNow(
        device: ConnectedDevice,
        applicationId: String,
        build: FlutterBuild,
        budgetMs: Long,
    ): FlutterAttachOutcome {
        if (build == FlutterBuild.RELEASE) return FlutterAttachOutcome.ReleaseBuild(device.serialNumber, applicationId)
        val startedAt = synchronized(lock) {
            processStartedAt.takeIf { target?.serial == device.serialNumber && target?.applicationId == applicationId }
        }
        var outcome: FlutterAttachOutcome? = null
        val call = background(
            Runnable { outcome = attach.ensure(device, applicationId, startedAt, build, recordHttp()) },
        )
        val answer = try {
            call.get(budgetMs, TimeUnit.MILLISECONDS)
            outcome
        } catch (_: TimeoutException) {
            null
        } catch (e: ExecutionException) {
            FlutterAttachOutcome.Failed(e.cause?.message ?: "The attach failed.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } ?: FlutterAttachOutcome.NotReady(STILL_ATTACHING, BACKOFF_MS.first())
        if (answer is FlutterAttachOutcome.NotReady) keepTrying(device.serialNumber, applicationId)
        return answer
    }

    override fun dispose() {
        synchronized(lock) {
            disposed = true
            generation++
            pending?.cancel(false)
            pending = null
            target = null
        }
        scheduler.shutdown()
    }

    /** Under [lock]: a new generation for [next], tried at once. */
    private fun restart(next: Target?, startedAt: Long?) {
        generation++
        pending?.cancel(false)
        pending = null
        target = next
        processStartedAt = startedAt
        pids = null
        attempt = 0
        if (next != null) schedule(0)
    }

    /** Under [lock]. */
    private fun schedule(delayMs: Long) {
        val generationNow = generation
        pending = scheduler.schedule(delayMs, Runnable { run(generationNow) })
    }

    /** After Diagnose's NotReady: the selected app gets an attempt soon, unless one is already due. */
    private fun keepTrying(serial: String, applicationId: String) = synchronized(lock) {
        val now = target ?: return@synchronized
        if (disposed || now.serial != serial || now.applicationId != applicationId) return@synchronized
        if (pending?.isDone == false) return@synchronized
        attempt = 0
        schedule(BACKOFF_MS.first())
    }

    // An attempt that throws — a bug, adb going away — must not end the follower's thread.
    @Suppress("TooGenericExceptionCaught")
    private fun run(generationNow: Long) {
        try {
            attemptOnce(generationNow)
        } catch (e: RuntimeException) {
            log.warn("Attaching to the Flutter app failed", e)
        }
    }

    private fun attemptOnce(generationNow: Long) {
        val now = synchronized(lock) { target?.takeIf { generation == generationNow && !disposed } } ?: return
        val build = buildOf(now.device, now.applicationId)
        // Not Flutter, or a release build: nothing to attach to, and nothing said about it.
        if (build == null || build == FlutterBuild.RELEASE) return
        val key = keyToTry(now, generationNow) ?: return
        val startedAt = synchronized(lock) { processStartedAt }
        val outcome = attach.ensure(now.device, now.applicationId, startedAt, build, recordHttp())
        synchronized(lock) {
            if (generation != generationNow) return
            lastOutcome = outcome
            when (outcome) {
                is FlutterAttachOutcome.NotReady -> retry(outcome.retryAfterMs)
                is FlutterAttachOutcome.NoDdsSession, is FlutterAttachOutcome.NotFound -> terminal[key] = outcome
                else -> pending = null
            }
        }
    }

    /**
     * What to remember this attempt's outcome by, from the app's pids now; null when there is
     * nothing to try: the app is not running, adb failed (retried), the selection moved on, or a
     * terminal outcome is remembered for these pids.
     */
    private fun keyToTry(now: Target, generationNow: Long): Key? {
        val found = pidsOf(now.device, now.applicationId)
        synchronized(lock) {
            if (generation != generationNow) return null
            if (found == null) {
                retry(null)
                return null
            }
            if (found.isEmpty()) return null
            // A new process starts the backoff over: what the old one taught is not about it.
            if (found != pids) attempt = 0
            pids = found
            return Key(now.serial, now.applicationId, found).takeIf { it !in terminal }
        }
    }

    /** Under [lock]: the next attempt after the backoff step, or none once the steps are spent. */
    private fun retry(suggestedMs: Long?) {
        if (attempt >= MAX_ATTEMPTS) {
            pending = null
            return
        }
        val step = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
        attempt++
        schedule(maxOf(step, suggestedMs?.coerceAtMost(MAX_DELAY_MS) ?: 0))
    }

    companion object {
        /** ≈0.5, 1, 2, 4 s; the last step repeats. */
        val BACKOFF_MS = listOf(500L, 1_000L, 2_000L, 4_000L)

        /**
         * Enough to outlast the service's startup window ([FlutterSessionService.STARTUP_GRACE_MS])
         * from the first attempt, after which it answers with something final.
         */
        const val MAX_ATTEMPTS = 8

        /** The longest wait the service's suggestion is followed for. */
        const val MAX_DELAY_MS = 10_000L

        const val STILL_ATTACHING = "Spock is still connecting to the app's Dart VM Service: the app is starting, " +
            "or `flutter run` is still attaching."

        private const val MAX_REMEMBERED = 32
        private const val IDLE_SECONDS = 30L
        private val log = Logger.getInstance(FlutterFollower::class.java)
    }
}
