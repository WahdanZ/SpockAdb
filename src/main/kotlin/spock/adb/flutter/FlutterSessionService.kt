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
import spock.adb.device.ConnectedDevice
import spock.adb.flutter.dtd.DtdAppIdentity
import spock.adb.flutter.dtd.DtdDiscovery
import spock.adb.flutter.vmservice.LogcatDiscovery
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.pidsOf
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.android.ddmlib.TimeoutException as AdbTimeoutException

/**
 * The project's connection to a running Flutter app: at most one [FlutterSession], found
 * through the sources that know the app's address, confirmed to be the selected app by process
 * (plan H2), and closed with the project.
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
    internal var dtdDiscovery: (projectPath: String, deviceModel: String?) -> VmServiceDiscovery =
        { path, model -> DtdDiscovery(path, model) }
    internal var logcatDiscovery: (IDevice, String) -> VmServiceDiscovery = ::LogcatDiscovery
    internal var appPids: (IDevice, String) -> Set<Long> =
        { device, id -> device.pidsOf(id, ADB_SECONDS).mapNotNull(String::toLongOrNull).toSet() }
    internal var dtdIdentity: DtdAppIdentity = DtdAppIdentity()

    /** Until #159's H1 probe lands: a direct candidate the VM did not hand to DDS has no DDS. */
    internal var ddsProbe: (FlutterSession) -> DdsProbeResult =
        { if (it.readOnly) DdsProbeResult.DirectNoDds else DdsProbeResult.Dds }
    internal var newSession: () -> FlutterSession = { FlutterSession() }
    internal var background: (Runnable) -> Future<*> = { ApplicationManager.getApplication().executeOnPooledThread(it) }
    internal var onEdt: () -> Boolean = { ApplicationManager.getApplication()?.isDispatchThread == true }
    internal var clock: () -> Long = System::currentTimeMillis
    internal var closeWaitMs: Long = CLOSE_WAIT_MS

    private class Held(val session: FlutterSession, val identity: AppIdentity?)

    /** One process's start, followed across [ensureSession] calls until its pids change. */
    private class Startup(val pids: Set<Long>, val startedAt: Long) {
        @Volatile
        var lastDirectProbeAt: Long? = null

        /** Set once the process is known to run without DDS: terminal for these pids. */
        @Volatile
        var noDds: AppIdentity? = null
    }

    /** Guards the fields below. Never held across a connect, a close or a listener. */
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<FlutterSessionServiceListener>()
    private val startups = object : LinkedHashMap<String, Startup>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Startup>?) = size > MAX_STARTUPS
    }

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

    /** Calls [listener] on every change of [current] until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: FlutterSessionServiceListener) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Every address Spock could connect to for [applicationId] on [device], best first:
     * 1. the app a Dart Tooling Daemon lists for this project whose VM runs as the app's pid on
     *    [device] — DDS's address (needs an SDK whose DTD has `ConnectedApp`; 3.47.5 confirmed);
     * 2. [pasted], when the developer gave one — taken on their word, not probed;
     * 3. the engine's announcement in logcat by the app's pid — the VM's direct address, asked
     *    for only when nothing above found the app.
     *
     * With no [applicationId] nothing can be confirmed: a Dart Tooling Daemon's app is offered,
     * unverified, only when it is the one app listed for this project and device. A source that
     * fails is logged and skipped; [FlutterDiscoveryResult.notes] say what was missing. Blocking.
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
        val dtd = confirmedDtd(device, appId, pids, notes)
        val logcat = if (dtd.isEmpty() && pastedFound.isEmpty()) {
            logcatFound(device, appId, pids, notes)
        } else {
            emptyList()
        }
        return FlutterDiscoveryResult(dtd + pastedFound + logcat, notes)
    }

    /**
     * Makes sure the current session is [applicationId]'s on [device] — the automatic flow, with
     * no pasted address. Reads `pidof` on [device] first, every call: a session already on one of
     * those pids is returned as it is; a changed pid drops what the last attempt learned.
     *
     * Then DTD, confirmed by pid, else logcat. While the process is younger than
     * [STARTUP_GRACE_MS] — from [processStartedAt], a host-clock time the caller saw it start,
     * else from the first call for these pids — a missing address, an unreachable VM and a VM
     * without DDS are [FlutterAttachOutcome.NotReady]: `flutter run` may still be attaching, and a
     * client on the direct VM then would keep its DDS out (spike S10), so the direct VM is probed
     * at most every [DIRECT_PROBE_INTERVAL_MS]. Past the window a VM without DDS is
     * [FlutterAttachOutcome.NoDdsSession], never kept, and answered again without a probe until
     * the pid changes.
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
                val startup = startupOf(serial, applicationId, pids, processStartedAt)
                startup.noDds?.let { FlutterAttachOutcome.NoDdsSession(it, NO_DDS_MESSAGE) }
                    ?: attachFromEvidence(device, applicationId, startup, notes)
            }
        }
    }

    /** [ensureSession] once the process is known: DTD confirmed by pid, else logcat, gated by the startup window. */
    private fun attachFromEvidence(
        device: ConnectedDevice,
        applicationId: String,
        startup: Startup,
        notes: MutableList<String>,
    ): FlutterAttachOutcome {
        val now = clock()
        val inWindow = now - startup.startedAt < STARTUP_GRACE_MS
        val dtd = confirmedDtd(device, applicationId, startup.pids, notes).firstOrNull()
        if (dtd != null) return attach(dtd, startup, inWindow)
        val logcat = logcatFound(device, applicationId, startup.pids, notes).firstOrNull()
        if (logcat == null) {
            val reason = notes.joinToString(" ")
            return if (inWindow) {
                FlutterAttachOutcome.NotReady(reason, RETRY_MS)
            } else {
                FlutterAttachOutcome.NotFound(reason)
            }
        }
        val lastProbe = startup.lastDirectProbeAt
        if (inWindow && lastProbe != null && now - lastProbe < DIRECT_PROBE_INTERVAL_MS) {
            return FlutterAttachOutcome.NotReady(
                "Waiting before asking the app's VM again: `flutter run` may still be attaching.",
                DIRECT_PROBE_INTERVAL_MS - (now - lastProbe),
            )
        }
        startup.lastDirectProbeAt = now
        return attach(logcat, startup, inWindow)
    }

    /**
     * Connects to [candidate] for [identity], closing the session before it — and any connect
     * still opening — first. Kept whatever the connection is: a direct VM stays read-only
     * ([FlutterSession.readOnly]). Blocking.
     *
     * @throws VmServiceException when the connection fails, or another connect or [disconnect]
     * ended it first; [current] is then null.
     * @throws IllegalStateException once the project is closing.
     */
    fun connect(candidate: VmServiceCandidate, identity: AppIdentity? = null): FlutterSession {
        checkOffEdt()
        return checkNotNull(open(candidate, identity) { true }) { "A session kept by choice is never refused." }.session
    }

    /** [connect] to what [discover] found. */
    fun connect(found: IdentifiedCandidate): FlutterSession = connect(found.candidate, found.identity)

    /**
     * Closes the session, if any, and a connect still opening: undoes what it changed in the app
     * and drops the connection. Blocking.
     */
    fun disconnect() {
        checkOffEdt()
        val (old, opening) = synchronized(lock) {
            (held to connecting).also {
                held = null
                connecting = null
            }
        }
        opening?.close()
        old?.session?.close()
        old?.let { notify(FlutterSessionChange.Disconnected(it.session, it.identity, "Disconnected by Spock.")) }
    }

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

    /** The DTD app running as one of [pids], at most one. */
    private fun confirmedDtd(
        device: ConnectedDevice,
        applicationId: String,
        pids: Set<Long>,
        notes: MutableList<String>,
    ): List<IdentifiedCandidate> {
        val listed = dtdCandidates(device)
        val confirmed = dtdIdentity.confirm(listed, pids)
        if (confirmed == null) {
            notes += if (listed.isEmpty()) {
                "No Dart Tooling Daemon lists an app of this project on such a device."
            } else {
                "None of the ${listed.size} apps Dart Tooling Daemons list runs as $applicationId " +
                    "on ${device.serialNumber}."
            }
            return emptyList()
        }
        val identity = AppIdentity(device.serialNumber, applicationId, confirmed.pid, IdentityCheck.DTD_PID)
        return listOf(IdentifiedCandidate(confirmed.candidate, identity))
    }

    private fun dtdCandidates(device: ConnectedDevice): List<VmServiceCandidate> = projectPath()?.let { path ->
        run("the Dart Tooling Daemon", dtdDiscovery(path, device.info.model.takeIf { it.isNotBlank() }))
    }.orEmpty()

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

    /** Connects to [found] and keeps it if it is DDS's; maps what it is not to an outcome. */
    private fun attach(found: IdentifiedCandidate, startup: Startup, inWindow: Boolean): FlutterAttachOutcome {
        var probe: DdsProbeResult = DdsProbeResult.Dds
        val kept = try {
            open(found.candidate, found.identity) { session ->
                probe = ddsProbe(session)
                probe == DdsProbeResult.Dds
            }
        } catch (e: VmServiceException) {
            probe = DdsProbeResult.Unreachable(e.message.orEmpty())
            null
        } catch (e: IllegalStateException) {
            return FlutterAttachOutcome.Failed(e.message.orEmpty())
        }
        if (kept != null) return FlutterAttachOutcome.Connected(kept.session, kept.identity ?: found.identity, false)
        return when (val result = probe) {
            is DdsProbeResult.Unreachable -> if (inWindow) {
                FlutterAttachOutcome.NotReady("The app's VM Service did not answer yet: ${result.reason}", RETRY_MS)
            } else {
                FlutterAttachOutcome.Failed(result.reason)
            }
            else -> if (inWindow) {
                FlutterAttachOutcome.NotReady(
                    "The app's VM has no DDS yet: `flutter run` may still be attaching.",
                    RETRY_MS,
                )
            } else {
                startup.noDds = found.identity
                FlutterAttachOutcome.NoDdsSession(found.identity, NO_DDS_MESSAGE)
            }
        }
    }

    /**
     * Opens a session on [candidate] without the lock, published as [connecting] meanwhile, and
     * makes it [current] when [keep] says so; else closes it and returns null.
     */
    private fun open(candidate: VmServiceCandidate, identity: AppIdentity?, keep: (FlutterSession) -> Boolean): Held? {
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
        var result: Held? = null
        try {
            session.connect(candidate)
            if (!keep(session)) return null
            val withPid = identity?.let { if (it.pid != null) it else it.copy(pid = DtdAppIdentity.pidOf(session.vm)) }
            result = synchronized(lock) {
                if (connecting !== session || disposed) return@synchronized null
                connecting = null
                Held(session, withPid).also { held = it }
            }
            if (result == null) {
                throw VmServiceClosedException("another connection took its place, or the project closed")
            }
            watch(session)
            notify(
                previous?.let { FlutterSessionChange.Replaced(it.session, it.identity, session, withPid) }
                    ?: FlutterSessionChange.Connected(session, withPid),
            )
            return result
        } finally {
            if (result == null) {
                synchronized(lock) { if (connecting === session) connecting = null }
                session.close()
                previous?.let {
                    notify(FlutterSessionChange.Disconnected(it.session, it.identity, "Closed for a new connection."))
                }
            }
        }
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

    private fun startupOf(serial: String, applicationId: String, pids: Set<Long>, startedAt: Long?): Startup =
        synchronized(lock) {
            val key = startupKey(serial, applicationId)
            startups[key]?.takeIf { it.pids == pids } ?: Startup(pids, startedAt ?: clock()).also { startups[key] = it }
        }

    private fun startupKey(serial: String, applicationId: String) = "$serial\u0000$applicationId"

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

        /** Inside the startup window, the least time between two connections to a direct VM. */
        const val DIRECT_PROBE_INTERVAL_MS = 4_000L

        /** The retry suggested with [FlutterAttachOutcome.NotReady]; callers back off from it. */
        const val RETRY_MS = 1_000L

        /** How long [dispose] waits for the close that restores the app's HTTP logging. */
        const val CLOSE_WAIT_MS = 1_000L

        private const val ADB_SECONDS = 10L
        private const val MAX_STARTUPS = 16

        const val NO_DDS_MESSAGE = "The app is running without a debugger session: start it with `flutter run` " +
            "or `flutter attach`. Spock does not stay connected to it, which would keep them from attaching."

        /**
         * Not the inline `project.service<T>()`, which inlines a call missing before 2023.3
         * (docs/COMPATIBILITY.md).
         */
        fun getInstance(project: Project): FlutterSessionService =
            project.getService(FlutterSessionService::class.java)
    }
}
