package spock.adb.flutter

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import spock.adb.device.ConnectedDevice
import spock.adb.flutter.vmservice.Redaction
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Decides **when** to attach to the selected Flutter app (design §2), so a developer never
 * pastes an address: on a selection change, when the selected app's process starts, when its
 * session is lost or taken by another app's, and when Diagnose asks. It only calls
 * [FlutterSessionService.ensureSession]; which session that gives, and how, is the service's
 * business — including what it remembers about a process with no DDS.
 *
 * Gates first, so nothing is attempted against an app that has no VM Service to find: the
 * selected app must be a Flutter app, by its APK, and not a release build.
 *
 * Every trigger starts a run with a time budget, [FOLLOW_BUDGET_MS] from its anchor — the
 * process start when known — rather than a count of attempts: a slow device takes longer, not
 * more tries. Within it, an answer that is not final is asked again with backoff ([BACKOFF_MS],
 * the last step repeating, or the service's own `retryAfterMs` when longer): not ready, nothing
 * found yet, failed, and — right after a process start, before Android has named the process —
 * not running. [FlutterAttachOutcome.NoDdsSession] is final for the process but not for ever:
 * it is asked again every [NO_DDS_RECHECK_MS], which costs adb reads and no VM contact until a
 * Flutter tool forwards the VM, up to [NO_DDS_RECHECKS] times. A run stops on a selection
 * change, a new trigger, or [dispose].
 *
 * Thread-safe. Everything blocking runs on [scheduler] or the caller's pooled thread.
 */
// Each seam is one collaborator; a holder object would only rename them.
@Suppress("LongParameterList")
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
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where decisions are logged, at INFO; every line is scrubbed of VM Service addresses first. */
    private val info: (String) -> Unit = { log.info(it) },
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

    /** Why a run started, for the log and for what "not running" means in it. */
    enum class Trigger(val label: String, val expectsProcess: Boolean) {
        SELECTION("selection changed", false),
        PROCESS_START("process started", true),
        SESSION_LOST("session lost", true),
        SESSION_TAKEN("another app's session took its place", false),
    }

    private data class Target(val device: ConnectedDevice, val applicationId: String) {
        val serial: String get() = device.serialNumber
        override fun toString(): String = "$applicationId on $serial"
    }

    private val lock = Any()

    /** Bumped by every trigger and by [dispose]: an attempt of an older generation stops. */
    private var generation = 0L
    private var target: Target? = null
    private var trigger = Trigger.SELECTION
    private var processStartedAt: Long? = null
    private var anchor = 0L
    private var pids: Set<Long>? = null
    private var step = 0
    private var noDdsChecks = 0
    private var pending: Future<*>? = null
    private var disposed = false

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
            lastOutcome = null
            restart(next, Trigger.SELECTION, startedAt = null)
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
            restart(now, Trigger.PROCESS_START, startedAt = hostMs)
        }
    }

    /**
     * The project's session changed. The selected app's session lost — the app stopped, `flutter
     * run` ended — starts a run, as a process start would, with no device log needed. Another
     * app's session taking its place (another package's Diagnose) starts one after
     * [REFOLLOW_DELAY_MS], so that report reads its session first. Cheap.
     */
    fun sessionChanged(change: FlutterSessionChange) {
        synchronized(lock) {
            val now = target ?: return
            if (disposed) return
            val lost = change is FlutterSessionChange.Disconnected && isTarget(change.identity, now) &&
                change.reason != FlutterSessionService.DISCONNECTED_BY_SPOCK
            val taken = when (change) {
                is FlutterSessionChange.Connected -> !isTarget(change.identity, now)
                is FlutterSessionChange.Replaced ->
                    isTarget(change.previousIdentity, now) && !isTarget(change.identity, now)
                is FlutterSessionChange.Disconnected -> false
            }
            when {
                lost -> restart(now, Trigger.SESSION_LOST, startedAt = null)
                taken -> restart(now, Trigger.SESSION_TAKEN, startedAt = null, delayMs = REFOLLOW_DELAY_MS)
            }
        }
    }

    /**
     * Diagnose's trigger: attaches to [applicationId] on [device] now and waits at most
     * [budgetMs] for the answer. Past it, the attach goes on, and the answer is
     * [FlutterAttachOutcome.NotReady]. A NotReady for the selected app keeps the backoff going,
     * so the next report finds the session. Blocking: call from a pooled thread.
     */
    fun attachNow(
        device: ConnectedDevice,
        applicationId: String,
        build: FlutterBuild,
        budgetMs: Long,
    ): FlutterAttachOutcome {
        say("Diagnose asks for $applicationId on ${device.serialNumber} (${build.label} build)")
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
        say("Diagnose's attach for $applicationId on ${device.serialNumber}: ${answer.logLine()}")
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

    /** Under [lock]: a new run for [next], tried after [delayMs]. */
    private fun restart(next: Target?, why: Trigger, startedAt: Long?, delayMs: Long = 0) {
        generation++
        pending?.cancel(false)
        pending = null
        target = next
        trigger = why
        processStartedAt = startedAt
        anchor = startedAt ?: clock()
        pids = null
        step = 0
        noDdsChecks = 0
        if (next == null) return
        say("${why.label}: following $next" + if (delayMs > 0) " in $delayMs ms" else "")
        schedule(delayMs)
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
        step = 0
        anchor = maxOf(anchor, clock())
        retry(null, "Diagnose found it not ready")
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
        if (build == null || build == FlutterBuild.RELEASE) {
            say("$now is ${build?.let { "a ${it.label} build" } ?: "not a Flutter app"}: not attaching")
            return
        }
        if (!running(now, generationNow)) return
        val startedAt = synchronized(lock) { processStartedAt }
        val outcome = attach.ensure(now.device, now.applicationId, startedAt, build, recordHttp())
        synchronized(lock) {
            if (generation != generationNow) return
            lastOutcome = outcome
            say("$now (${build.label} build): ${outcome.logLine()}")
            decide(outcome)
        }
    }

    /**
     * Whether the app runs as some pid now. When not, a retry is scheduled if adb failed, or if
     * the run expects a process: a process start is logged before Android has named the process,
     * so `pidof` finds nothing for a moment — 2 s on emulator-5554 (2026-10-02), long enough to
     * end the first follower here.
     */
    private fun running(now: Target, generationNow: Long): Boolean {
        val found = pidsOf(now.device, now.applicationId)
        synchronized(lock) {
            if (generation != generationNow) return false
            when {
                found == null -> retry(null, "adb could not list $now's processes")
                found.isEmpty() && trigger.expectsProcess -> retry(null, "$now is not running yet")
                found.isEmpty() -> say("$now is not running; waiting for its process to start")
                else -> {
                    // A new process starts the backoff over: what the old one taught is not about it.
                    if (found != pids) {
                        step = 0
                        noDdsChecks = 0
                    }
                    pids = found
                    return true
                }
            }
            return false
        }
    }

    /** Under [lock]: what to do after [outcome]. */
    private fun decide(outcome: FlutterAttachOutcome) {
        when (outcome) {
            is FlutterAttachOutcome.NotReady -> retry(outcome.retryAfterMs, "not ready")
            is FlutterAttachOutcome.NotFound -> retry(null, "nothing found yet")
            is FlutterAttachOutcome.Failed -> retry(null, "the attach failed")
            is FlutterAttachOutcome.NotRunning ->
                if (trigger.expectsProcess) retry(null, "not running yet") else pending = null
            is FlutterAttachOutcome.NoDdsSession -> recheckNoDds()
            is FlutterAttachOutcome.Connected,
            is FlutterAttachOutcome.Ambiguous,
            is FlutterAttachOutcome.ReleaseBuild,
            -> pending = null
        }
    }

    /** Under [lock]: the next attempt after the backoff step, or none once the run's time is spent. */
    private fun retry(suggestedMs: Long?, why: String) {
        val stepMs = BACKOFF_MS[step.coerceAtMost(BACKOFF_MS.lastIndex)]
        val delay = maxOf(stepMs, suggestedMs?.coerceAtMost(MAX_DELAY_MS) ?: 0)
        val spent = clock() - anchor
        if (spent + delay > FOLLOW_BUDGET_MS) {
            say("giving up on ${target ?: "the app"} after $spent ms: $why")
            pending = null
            return
        }
        step++
        say("$why; asking again in $delay ms")
        schedule(delay)
    }

    /** Under [lock]: no DDS now is not no DDS for ever — someone may run `flutter attach`. */
    private fun recheckNoDds() {
        if (noDdsChecks >= NO_DDS_RECHECKS) {
            say("no debugger session after $noDdsChecks checks; asking again at the next trigger")
            pending = null
            return
        }
        noDdsChecks++
        say("no debugger session; checking again in $NO_DDS_RECHECK_MS ms")
        schedule(NO_DDS_RECHECK_MS)
    }

    private fun isTarget(identity: AppIdentity?, target: Target): Boolean =
        identity?.serial == target.serial && identity.applicationId == target.applicationId

    /** Every line goes through here: a VM Service address in a reason must not reach the log. */
    private fun say(line: String) = info(Redaction.scrub("Flutter follower: $line"))

    companion object {
        /** ≈0.5, 1, 2, 4 s; the last step repeats. */
        val BACKOFF_MS = listOf(500L, 1_000L, 2_000L, 4_000L)

        /**
         * How long one run keeps asking. It must outlast the service's waits: a VM announced up
         * to [AppStartup.STARTUP_GRACE_MS] after its process, and a Flutter tool attaching for up to
         * [AppStartup.TOOL_ATTACH_CAP_MS] after that, with room to spare.
         */
        const val FOLLOW_BUDGET_MS = AppStartup.STARTUP_GRACE_MS + AppStartup.TOOL_ATTACH_CAP_MS + 15_000L

        /** The longest wait the service's suggestion is followed for. */
        const val MAX_DELAY_MS = 10_000L

        /** How often a process with no debugger session is asked about again. */
        const val NO_DDS_RECHECK_MS = 30_000L

        /** Ten minutes of re-checks; a selection change or a new process starts them again. */
        const val NO_DDS_RECHECKS = 20

        /** How long another app's session is left before the selected app's is opened again. */
        const val REFOLLOW_DELAY_MS = 15_000L

        const val STILL_ATTACHING = "Spock is still connecting to the app's Dart VM Service: the app is starting, " +
            "or `flutter run` is still attaching."

        private const val IDLE_SECONDS = 30L
        private val log = Logger.getInstance(FlutterFollower::class.java)
    }
}
