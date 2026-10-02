package spock.adb.flutter

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import spock.adb.ShellOutputReceiver
import spock.adb.device.ConnectedDevice
import spock.adb.flutter.dtd.DtdAppIdentity
import spock.adb.flutter.dtd.DtdCandidate
import spock.adb.flutter.dtd.DtdDiscovery
import spock.adb.flutter.vmservice.LogcatDiscovery
import spock.adb.flutter.vmservice.NoDdsException
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.pidsOf
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/**
 * The project's connection to a running Flutter app: at most one [FlutterSession], found
 * through the sources that know the app's address, confirmed to be the selected app by process
 * on the selected device (plan H2), and closed with the project.
 *
 * Callers — Diagnose, the Timeline and the MCP tools — call [discover], [ensureSession],
 * [connect] and [disconnect] from a pooled thread: each one blocks on files, `adb` or the
 * network, and each refuses to run on the EDT. Nothing here touches Swing. [dispose] is the
 * exception: it runs where the project closes, hands the close to a pooled thread and waits at
 * most [CLOSE_WAIT_MS] for it.
 */
@Service(Service.Level.PROJECT)
class FlutterSessionService(private val project: Project) : Disposable {

    /** Seams for tests; production uses the real sources. */
    internal var projectPath: () -> String? = { project.basePath }
    internal var dtdDiscovery: (projectPath: String, deviceModel: String?) -> List<DtdCandidate> =
        { path, model -> DtdDiscovery(path, model).candidates() }
    internal var logcatDiscovery: (IDevice, String) -> VmServiceDiscovery = ::LogcatDiscovery

    /**
     * `pidof` needs toybox, from Android 6: on API 21–22 the shell has none, it prints an error,
     * and the app reads as not running there. Not worked around.
     */
    internal var appPids: (IDevice, String) -> Set<Long> =
        { device, id -> device.pidsOf(id, ADB_SECONDS).mapNotNull(String::toLongOrNull).toSet() }
    internal var processTiming: (IDevice, Long) -> ProcessTiming? = ::readProcessTiming
    internal var dtdIdentity: DtdAppIdentity = DtdAppIdentity()
    internal var newSession: () -> FlutterSession = { FlutterSession() }
    internal var background: (Runnable) -> Future<*> = { ApplicationManager.getApplication().executeOnPooledThread(it) }
    internal var onEdt: () -> Boolean = { ApplicationManager.getApplication()?.isDispatchThread == true }
    internal var clock: () -> Long = System::currentTimeMillis
    internal var closeWaitMs: Long = CLOSE_WAIT_MS

    private class Held(val session: FlutterSession, val identity: AppIdentity?)

    /**
     * One process's start, followed across [ensureSession] calls until its pids change. Read and
     * written only under that app's attach lock, so the probe spacing is atomic.
     */
    private class Startup(val pids: Set<Long>, val startedAt: Long) {
        var lastDirectProbeAt: Long? = null

        /** Set once the process is known to run without DDS: no more direct probes for these pids. */
        var noDds: AppIdentity? = null
    }

    /** What the Dart Tooling Daemons say about the selected app. */
    private sealed interface DtdEvidence {
        class Found(val found: IdentifiedCandidate) : DtdEvidence

        class Ambiguous(val candidates: List<IdentifiedCandidate>) : DtdEvidence

        object None : DtdEvidence
    }

    /** Guards the fields below. Never held across a connect, a close or a listener. */
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<FlutterSessionServiceListener>()
    private val startups = object : LinkedHashMap<String, Startup>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Startup>?) = size > MAX_STARTUPS
    }

    /**
     * One per (serial, applicationId) — a handful per project — serialising [ensureSession] for
     * that app. Not [lock]: [disconnect] and [dispose] never wait on an attach.
     */
    private val attachLocks = ConcurrentHashMap<String, ReentrantLock>()

    @Volatile
    private var disposed = false

    @Volatile
    private var held: Held? = null

    /** A [connect] still opening, published so [disconnect] and [dispose] can end it at once. */
    @Volatile
    private var connecting: FlutterSession? = null

    @Volatile
    private var lostReported: FlutterSession? = null

    /** The live or last session; null before the first [connect] and after [disconnect]. */
    val current: FlutterSession? get() = held?.session

    /** Which app [current] is for — null when connected without one. */
    val identity: AppIdentity? get() = held?.identity

    /**
     * Calls [listener] on every change of [current] until [parent] is disposed. [parent] must not
     * outlive the project — a tool window's content, a project service — or the listener stays
     * until the project closes. A [parent] already disposed registers nothing.
     */
    fun addListener(parent: Disposable, listener: FlutterSessionServiceListener) {
        if (!Disposer.tryRegister(parent, Disposable { listeners -= listener })) return
        listeners += listener
    }

    /**
     * Every address Spock could connect to for [applicationId] on [device], best first:
     * 1. the app a Dart Tooling Daemon lists for this project whose VM is the app's process on
     *    [device] — DDS's address (needs an SDK whose DTD has `ConnectedApp`; 3.47.5 confirmed);
     * 2. [pasted], when the developer gave one — taken on their word, not probed;
     * 3. the engine's announcement in logcat by the app's pid — the VM's direct address, asked
     *    for only when nothing above found the app.
     *
     * Several DTD apps passing as the selected app are [FlutterDiscoveryResult.ambiguous], not
     * ranked. With no [applicationId] nothing can be confirmed: a Dart Tooling Daemon's app is
     * offered, unverified, only when it is the one app listed for this project and device. A
     * source that fails is logged and skipped; [FlutterDiscoveryResult.notes] say what was
     * missing. Blocking.
     *
     * @throws IllegalArgumentException when [pasted] holds no usable address, before any other
     * source is asked — the message is fit to show.
     */
    fun discover(device: ConnectedDevice, applicationId: String?, pasted: String? = null): FlutterDiscoveryResult {
        checkOffEdt()
        val appId = applicationId?.takeIf { it.isNotBlank() }
        val pastedIdentity = AppIdentity(device.serialNumber, appId, null, IdentityCheck.PASTED_UNVERIFIED)
        val pastedFound = pasted?.takeIf { it.isNotBlank() }?.let { PastedUriDiscovery(it).discover() }.orEmpty()
            .map { IdentifiedCandidate(it, pastedIdentity) }
        if (appId == null) return unselected(device, pastedFound)
        val notes = mutableListOf<String>()
        val pids = pidsOrNull(device, appId, notes)
        if (pids != null && pids.isEmpty()) notes += "$appId is not running on ${device.serialNumber}."
        if (pids.isNullOrEmpty()) return FlutterDiscoveryResult(pastedFound, notes)
        val evidence = dtdEvidence(device, appId, pids.associateWith { processTiming(device.device, it) }, notes)
        val dtd = listOfNotNull((evidence as? DtdEvidence.Found)?.found)
        val logcat = if (dtd.isEmpty() && pastedFound.isEmpty()) {
            logcatFound(device, appId, pids, notes)
        } else {
            emptyList()
        }
        val ambiguous = (evidence as? DtdEvidence.Ambiguous)?.candidates.orEmpty()
        return FlutterDiscoveryResult(dtd + pastedFound + logcat, notes, ambiguous)
    }

    /**
     * Makes sure the current session is [applicationId]'s on [device] — the automatic flow, with
     * no pasted address. Only for an app P1 has detected as Flutter, which is why there is no
     * "not Flutter" outcome. Calls for one app run one at a time; each reads `pidof` on [device]
     * first: a session already on one of those pids is returned as it is, a changed pid drops
     * what the last attempt learned.
     *
     * Then DTD, confirmed by (pid, start) on [device] — several passing is
     * [FlutterAttachOutcome.Ambiguous] — else logcat. The startup window, [STARTUP_GRACE_MS],
     * runs from [processStartedAt] (host-clock ms) when the caller knows it, else from the
     * process's age as the device reports it, else from the first call for these pids. Inside it
     * a missing address, an unreachable VM and a VM without DDS are
     * [FlutterAttachOutcome.NotReady]: `flutter run` may still be attaching, and a client on the
     * direct VM then keeps its DDS out (spike S10) — so the direct VM is not connected to before
     * [DIRECT_PROBE_MIN_AGE_MS], nor twice within [DIRECT_PROBE_INTERVAL_MS]. Past the window a
     * VM without DDS is [FlutterAttachOutcome.NoDdsSession], never kept, and not probed again for
     * these pids — the DTD is still asked each time, so a later `flutter attach` is found.
     *
     * Never sleeps or loops: the caller retries [FlutterAttachOutcome.NotReady] with its own
     * backoff. [build] is the app's build when the caller knows it — a release build has no VM
     * Service. Blocking.
     */
    fun ensureSession(
        device: ConnectedDevice,
        applicationId: String,
        processStartedAt: Long? = null,
        build: FlutterBuild? = null,
    ): FlutterAttachOutcome {
        checkOffEdt()
        require(applicationId.isNotBlank()) { "No app is selected." }
        val attachLock = attachLocks.computeIfAbsent(startupKey(device.serialNumber, applicationId)) { ReentrantLock() }
        return attachLock.withLock { ensureLocked(device, applicationId, processStartedAt, build) }
    }

    /**
     * Connects to [candidate] for [identity], closing the session before it — and any connect
     * still opening — first. A VM with no DDS in front of it is refused unless [allowDirect]: then
     * it is kept read-only, and keeps `flutter run`/`flutter attach` from starting DDS until it is
     * closed (spike S10) — the developer's explicit choice. [recordHttp] as in
     * [FlutterSession.connect]. Blocking.
     *
     * @throws NoDdsException when the VM has no DDS and [allowDirect] is false; [current] is then
     * null.
     * @throws VmServiceException when the connection fails, or another connect or [disconnect]
     * ended it first; [current] is then null.
     * @throws IllegalStateException once the project is closing.
     */
    fun connect(
        candidate: VmServiceCandidate,
        identity: AppIdentity? = null,
        allowDirect: Boolean = false,
        recordHttp: Boolean = true,
    ): FlutterSession {
        checkOffEdt()
        val held = open(candidate, identity, allowDirect, recordHttp)
        return checkNotNull(held) { "A session with no pid to check is never refused." }.session
    }

    /** [connect] to what [discover] found. */
    fun connect(found: IdentifiedCandidate): FlutterSession = connect(found.candidate, found.identity)

    /**
     * Closes the session, if any, and a connect still opening: undoes what it changed in the app
     * and drops the connection. Forgets what [ensureSession] learned about each app's start, as
     * [resetAttach] does. Blocking.
     */
    fun disconnect() {
        checkOffEdt()
        val (old, opening) = synchronized(lock) {
            startups.clear()
            (held to connecting).also {
                held = null
                connecting = null
            }
        }
        opening?.close()
        old?.session?.close()
        old?.takeIf { lostReported !== it.session }?.let {
            notify(FlutterSessionChange.Disconnected(it.session, it.identity, "Disconnected by Spock."))
        }
    }

    /**
     * Forgets what [ensureSession] learned about each app's start — a "no DDS" verdict and the
     * direct-probe spacing — so the next call starts over: after the developer ran `flutter
     * attach`, say.
     */
    fun resetAttach() = synchronized(lock) { startups.clear() }

    /**
     * On project close or app exit, often on the EDT: the close talks to the app — it switches
     * HTTP logging back off — so it runs on a pooled thread, and this waits up to [closeWaitMs]
     * for it. A connect still opening is ended at once rather than waited for.
     */
    override fun dispose() {
        val sessions = synchronized(lock) {
            disposed = true
            startups.clear()
            listOfNotNull(connecting, held?.session).also {
                connecting = null
                held = null
            }
        }
        listeners.clear()
        if (sessions.isEmpty()) return
        val closing = background(Runnable { sessions.forEach(FlutterSession::close) })
        try {
            closing.get(closeWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            log.info("The Flutter session was still closing after $closeWaitMs ms; the app may keep HTTP logging on")
        } catch (e: ExecutionException) {
            log.warn("Closing the Flutter session failed", e.cause)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** [ensureSession] under the app's attach lock. */
    private fun ensureLocked(
        device: ConnectedDevice,
        applicationId: String,
        processStartedAt: Long?,
        build: FlutterBuild?,
    ): FlutterAttachOutcome {
        val serial = device.serialNumber
        val notes = mutableListOf<String>()
        val pids = if (build == FlutterBuild.RELEASE) null else pidsOrNull(device, applicationId, notes)
        return when {
            build == FlutterBuild.RELEASE -> FlutterAttachOutcome.ReleaseBuild(serial, applicationId)
            pids == null -> FlutterAttachOutcome.Failed(notes.joinToString(" "))
            pids.isEmpty() -> {
                synchronized(lock) { startups.remove(startupKey(serial, applicationId)) }
                FlutterAttachOutcome.NotRunning(serial, applicationId)
            }
            else -> reusable(serial, applicationId, pids) ?: run {
                val timings = pids.associateWith { processTiming(device.device, it) }
                val startup = startupOf(serial, applicationId, pids, processStartedAt, timings)
                attachFromEvidence(device, applicationId, startup, timings, notes)
            }
        }
    }

    /** DTD first — it only touches DDS — then, unless the process is known to have no DDS, logcat. */
    private fun attachFromEvidence(
        device: ConnectedDevice,
        applicationId: String,
        startup: Startup,
        timings: Map<Long, ProcessTiming?>,
        notes: MutableList<String>,
    ): FlutterAttachOutcome {
        val inWindow = clock() - startup.startedAt < STARTUP_GRACE_MS
        return when (val dtd = dtdEvidence(device, applicationId, timings, notes)) {
            is DtdEvidence.Found -> attach(dtd.found, startup, inWindow)
            is DtdEvidence.Ambiguous -> FlutterAttachOutcome.Ambiguous(dtd.candidates, notes.joinToString(" "))
            DtdEvidence.None -> startup.noDds?.let { FlutterAttachOutcome.NoDdsSession(it, NO_DDS_MESSAGE) }
                ?: attachDirect(device, applicationId, startup, inWindow, notes)
        }
    }

    /** The logcat address — the VM's own — connected to only when the probe rules allow. */
    private fun attachDirect(
        device: ConnectedDevice,
        applicationId: String,
        startup: Startup,
        inWindow: Boolean,
        notes: MutableList<String>,
    ): FlutterAttachOutcome {
        val logcat = logcatFound(device, applicationId, startup.pids, notes).firstOrNull()
        val now = clock()
        val wait = directProbeWait(startup, now, inWindow)
        return when {
            logcat == null -> if (inWindow) {
                FlutterAttachOutcome.NotReady(notes.joinToString(" "), RETRY_MS)
            } else {
                FlutterAttachOutcome.NotFound(notes.joinToString(" "))
            }
            wait != null -> wait
            else -> {
                startup.lastDirectProbeAt = now
                attach(logcat, startup, inWindow)
            }
        }
    }

    /** Why the direct VM must not be connected to yet, or null when it may be. */
    private fun directProbeWait(startup: Startup, now: Long, inWindow: Boolean): FlutterAttachOutcome.NotReady? {
        val age = now - startup.startedAt
        if (inWindow && age < DIRECT_PROBE_MIN_AGE_MS) {
            return FlutterAttachOutcome.NotReady(
                "The app started $age ms ago: waiting for `flutter run` to attach before asking its VM directly.",
                DIRECT_PROBE_MIN_AGE_MS - age,
            )
        }
        val since = now - (startup.lastDirectProbeAt ?: return null)
        if (since >= DIRECT_PROBE_INTERVAL_MS) return null
        return FlutterAttachOutcome.NotReady(
            "Waiting before asking the app's VM again: `flutter run` may still be attaching.",
            DIRECT_PROBE_INTERVAL_MS - since,
        )
    }

    /** [discover] with no app selected: nothing can be confirmed by pid, so nothing is guessed. */
    private fun unselected(device: ConnectedDevice, pasted: List<IdentifiedCandidate>): FlutterDiscoveryResult {
        val listed = dtdCandidates(device)
        val note = when (listed.size) {
            0 -> "No app is selected, and no Dart Tooling Daemon lists an app of this project on such a device."
            1 ->
                "No app is selected: the one app a Dart Tooling Daemon lists for this project and device " +
                    "is offered unverified."
            else ->
                "No app is selected, and Dart Tooling Daemons list ${listed.size} apps of this project on " +
                    "such a device: select the app so Spock can tell them apart by process."
        }
        val identity = AppIdentity(device.serialNumber, null, null, IdentityCheck.DTD_NAME_UNVERIFIED)
        val dtd = listOfNotNull(listed.singleOrNull()?.let { IdentifiedCandidate(it, identity) })
        return FlutterDiscoveryResult(dtd + pasted, listOf(note))
    }

    /** Which DTD app is the selected one: by pid and start on [device] ([DtdAppIdentity]). */
    private fun dtdEvidence(
        device: ConnectedDevice,
        applicationId: String,
        timings: Map<Long, ProcessTiming?>,
        notes: MutableList<String>,
    ): DtdEvidence {
        val serial = device.serialNumber
        val listed = dtdCandidates(device)
        return when (val result = dtdIdentity.confirm(listed, timings.mapValues { it.value?.startEpochMs })) {
            is DtdAppIdentity.Result.Confirmed -> {
                val check = if (result.startChecked) IdentityCheck.DTD_PID_START else IdentityCheck.DTD_PID_ONLY
                val identity = AppIdentity(serial, applicationId, result.pid, check)
                DtdEvidence.Found(IdentifiedCandidate(result.candidate, identity))
            }
            is DtdAppIdentity.Result.Ambiguous -> {
                notes += "${result.candidates.size} apps Dart Tooling Daemons list pass as $applicationId " +
                    "on $serial, and their starts cannot tell them apart: choose one."
                DtdEvidence.Ambiguous(
                    result.candidates.map {
                        IdentifiedCandidate(
                            it.candidate,
                            AppIdentity(serial, applicationId, it.pid, IdentityCheck.DTD_PID_AMBIGUOUS),
                        )
                    },
                )
            }
            DtdAppIdentity.Result.None -> {
                notes += if (listed.isEmpty()) {
                    "No Dart Tooling Daemon lists an app of this project on such a device."
                } else {
                    "None of the ${listed.size} apps Dart Tooling Daemons list is $applicationId's process on $serial."
                }
                DtdEvidence.None
            }
        }
    }

    // Exception, not one type: a bug in discovery must not cost the other sources.
    @Suppress("TooGenericExceptionCaught")
    private fun dtdCandidates(device: ConnectedDevice): List<DtdCandidate> {
        val path = projectPath() ?: return emptyList()
        return try {
            dtdDiscovery(path, device.info.model.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            val reason = Redaction.scrub(e.message.orEmpty())
            log.info("Flutter discovery through the Dart Tooling Daemon failed: $reason")
            emptyList()
        }
    }

    private fun logcatFound(
        device: ConnectedDevice,
        applicationId: String,
        pids: Set<Long>,
        notes: MutableList<String>,
    ): List<IdentifiedCandidate> {
        val identity = AppIdentity(device.serialNumber, applicationId, pids.singleOrNull(), IdentityCheck.LOGCAT_PID)
        val found = run("logcat", logcatDiscovery(device.device, applicationId))
            .map { IdentifiedCandidate(it, identity) }
        if (found.isEmpty()) notes += "$applicationId has not logged a VM Service address (a release build never does)."
        return found
    }

    /** `pidof` on the device; null, with a note saying why, when adb fails. */
    private fun pidsOrNull(device: ConnectedDevice, applicationId: String, notes: MutableList<String>): Set<Long>? {
        val failure: Exception = try {
            return appPids(device.device, applicationId)
        } catch (e: IOException) {
            e
        } catch (e: AdbCommandRejectedException) {
            e
        } catch (e: ShellCommandUnresponsiveException) {
            e
        } catch (e: AdbTimeoutException) {
            e
        }
        notes += "Could not list the processes of $applicationId on ${device.serialNumber}: " +
            (failure.message ?: failure.javaClass.simpleName)
        return null
    }

    /**
     * Connects to [found] — refused by the session when no DDS answers ([NoDdsException]) — and,
     * for a logcat address, keeps it only if its VM runs as one of the app's pids; maps what it
     * is not to an outcome.
     */
    private fun attach(found: IdentifiedCandidate, startup: Startup, inWindow: Boolean): FlutterAttachOutcome {
        val pidsAllowed = startup.pids.takeIf { found.identity.verifiedBy == IdentityCheck.LOGCAT_PID }
        val kept = try {
            open(found.candidate, found.identity, allowDirect = false, recordHttp = true, pidsAllowed = pidsAllowed)
        } catch (e: NoDdsException) {
            // Before VmServiceException, which it is: a VM with no DDS is an answer, not a failure.
            return if (inWindow) {
                FlutterAttachOutcome.NotReady(
                    "The app's VM has no DDS yet: `flutter run` may still be attaching.",
                    RETRY_MS,
                )
            } else {
                startup.noDds = found.identity
                FlutterAttachOutcome.NoDdsSession(found.identity, e.message ?: NO_DDS_MESSAGE)
            }
        } catch (e: VmServiceException) {
            return if (inWindow) {
                FlutterAttachOutcome.NotReady("The app's VM Service did not answer yet: ${e.message}", RETRY_MS)
            } else {
                FlutterAttachOutcome.Failed(e.message.orEmpty())
            }
        } catch (e: IllegalStateException) {
            return FlutterAttachOutcome.Failed(e.message.orEmpty())
        }
        return if (kept != null) {
            FlutterAttachOutcome.Connected(kept.session, kept.identity ?: found.identity, false)
        } else {
            FlutterAttachOutcome.Failed(
                "The VM at ${found.candidate} is not one of ${found.identity.applicationId}'s " +
                    "processes ${startup.pids}.",
            )
        }
    }

    /**
     * Opens a session on [candidate] without the lock, published as [connecting] meanwhile, and
     * makes it [current] — unless [pidsAllowed] is given and the VM runs as none of them: then it
     * is closed and null returned.
     */
    private fun open(
        candidate: VmServiceCandidate,
        identity: AppIdentity?,
        allowDirect: Boolean,
        recordHttp: Boolean,
        pidsAllowed: Set<Long>? = null,
    ): Held? {
        val session = newSession()
        val (previous, opening) = synchronized(lock) {
            check(!disposed) { "The project is closed." }
            (held to connecting).also {
                held = null
                connecting = session
            }
        }
        opening?.close()
        previous?.session?.close()
        // A previous session already reported lost was already reported gone: no second notice.
        val previousLive = previous?.takeIf { lostReported !== it.session }
        var result: Held? = null
        try {
            session.connect(candidate, allowDirect, recordHttp)
            // H1's pid is an Int; Spock's identity carries pids as Long, as `pidof` reads them.
            val vmPid = session.snapshot.vmPid?.toLong()
            if (pidsAllowed != null && vmPid !in pidsAllowed) return null
            val published = publish(session, identity?.let { if (it.pid != null) it else it.copy(pid = vmPid) })
            result = published
            val identityNow = published.identity
            notify(
                previousLive?.let { FlutterSessionChange.Replaced(it.session, it.identity, session, identityNow) }
                    ?: FlutterSessionChange.Connected(session, identityNow),
            )
            watch(session)
            return published
        } finally {
            if (result == null) {
                synchronized(lock) { if (connecting === session) connecting = null }
                session.close()
                previousLive?.let {
                    notify(FlutterSessionChange.Disconnected(it.session, it.identity, "Closed for a new connection."))
                }
            }
        }
    }

    /**
     * Makes [session] [current], unless another connect, a [disconnect] or [dispose] ended it
     * while it was opening.
     *
     * @throws VmServiceClosedException when one did.
     */
    private fun publish(session: FlutterSession, identity: AppIdentity?): Held = synchronized(lock) {
        if (connecting !== session || disposed) {
            throw VmServiceClosedException("another connection took its place, or the project closed")
        }
        connecting = null
        Held(session, identity).also { held = it }
    }

    /** Reports [session] lost when it drops while it is [current]; Spock's own closes report themselves. */
    private fun watch(session: FlutterSession) {
        session.addListener(object : FlutterSessionListener {
            override fun onEvent(event: FlutterEvent) = Unit

            override fun onStateChanged(state: SessionState) {
                if (state is SessionState.Disconnected) lost(session, state.reason)
            }
        })
        (session.state as? SessionState.Disconnected)?.let { lost(session, it.reason) }
    }

    private fun lost(session: FlutterSession, reason: String) {
        val identity = synchronized(lock) {
            val lostOne = held?.takeIf { it.session === session && lostReported !== session } ?: return
            lostReported = session
            lostOne.identity
        }
        notify(FlutterSessionChange.Disconnected(session, identity, reason))
    }

    // A listener's bug must not cost the connect that triggered it, nor the other listeners.
    @Suppress("TooGenericExceptionCaught")
    private fun notify(change: FlutterSessionChange) = listeners.forEach { listener ->
        try {
            listener.sessionChanged(change)
        } catch (e: RuntimeException) {
            log.warn("A Flutter session listener failed", e)
        }
    }

    /** The current session, when it is already the verified one for one of [pids] and still up. */
    private fun reusable(serial: String, applicationId: String, pids: Set<Long>): FlutterAttachOutcome.Connected? {
        val now = held ?: return null
        val id = now.identity ?: return null
        val same = id.verified && id.serial == serial && id.applicationId == applicationId && id.pid in pids
        if (!same || now.session.state is SessionState.Disconnected) return null
        return FlutterAttachOutcome.Connected(now.session, id, reused = true)
    }

    /**
     * What is known about these pids' start, or a new record: from the caller's [startedAt],
     * else the oldest pid's age on the device, else now.
     */
    private fun startupOf(
        serial: String,
        applicationId: String,
        pids: Set<Long>,
        startedAt: Long?,
        timings: Map<Long, ProcessTiming?>,
    ): Startup = synchronized(lock) {
        val key = startupKey(serial, applicationId)
        startups[key]?.takeIf { it.pids == pids } ?: run {
            val oldestAge = timings.values.mapNotNull { it?.ageMs }.maxOrNull()
            Startup(pids, startedAt ?: oldestAge?.let { clock() - it } ?: clock()).also { startups[key] = it }
        }
    }

    private fun checkOffEdt() = check(!onEdt()) {
        "The Flutter session blocks on adb and the network: call it from a pooled thread, not the EDT."
    }

    // Exception, not VmServiceException alone: logcat discovery runs adb, whose failures are
    // ddmlib's own checked exceptions, and one failing source must not cost the others.
    @Suppress("TooGenericExceptionCaught")
    private fun run(source: String, discovery: VmServiceDiscovery): List<VmServiceCandidate> = try {
        discovery.discover()
    } catch (e: Exception) {
        log.info("Flutter discovery through $source failed: ${Redaction.scrub(e.message.orEmpty())}")
        emptyList()
    }

    companion object {
        private val log = Logger.getInstance(FlutterSessionService::class.java)

        /** How long after a process starts a missing or DDS-less VM may still be `flutter run` attaching. */
        const val STARTUP_GRACE_MS = 10_000L

        /**
         * Inside the startup window, how long after the process starts its VM is first connected
         * to directly: `flutter run` starts DDS within the first seconds, and a direct client then
         * would keep it out (S10). DTD and logcat evidence come first.
         */
        const val DIRECT_PROBE_MIN_AGE_MS = 3_000L

        /** The least time between two direct connections to one app's VM, inside the window or not. */
        const val DIRECT_PROBE_INTERVAL_MS = DtdAppIdentity.DIRECT_PROBE_INTERVAL_MS

        /** The retry suggested with [FlutterAttachOutcome.NotReady]; callers back off from it. */
        const val RETRY_MS = 1_000L

        /** How long [dispose] waits for the close that restores the app's HTTP logging. */
        const val CLOSE_WAIT_MS = 1_000L

        private const val ADB_SECONDS = 10L

        private fun startupKey(serial: String, applicationId: String) = "$serial\u0000$applicationId"
        private const val MAX_STARTUPS = 16

        const val NO_DDS_MESSAGE = "The app is running without a debugger session: start it with `flutter run` " +
            "or `flutter attach`. Spock does not stay connected to it, which would keep them from attaching."

        /** The process's start on the device's clock, or null when its `/proc` is not readable. */
        private fun readProcessTiming(device: IDevice, pid: Long): ProcessTiming? {
            val receiver = ShellOutputReceiver()
            val failure: Exception = try {
                device.executeShellCommand(ProcessTiming.command(pid), receiver, ADB_SECONDS, TimeUnit.SECONDS)
                return ProcessTiming.parse(receiver.toString())
            } catch (e: IOException) {
                e
            } catch (e: AdbCommandRejectedException) {
                e
            } catch (e: ShellCommandUnresponsiveException) {
                e
            } catch (e: AdbTimeoutException) {
                e
            }
            log.info("Could not read when pid $pid started: ${failure.message ?: failure.javaClass.simpleName}")
            return null
        }

        /**
         * Not the inline `project.service<T>()`, which inlines a call missing before 2023.3
         * (docs/COMPATIBILITY.md).
         */
        fun getInstance(project: Project): FlutterSessionService =
            project.getService(FlutterSessionService::class.java)
    }
}
