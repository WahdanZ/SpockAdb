package spock.adb.flutter

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.HandedToDdsCandidate
import spock.adb.flutter.vmservice.NoDdsException
import spock.adb.flutter.vmservice.NoUiIsolateException
import spock.adb.flutter.vmservice.ReadOnlyConnectionException
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServiceEvent
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceListener
import spock.adb.flutter.vmservice.VmServicePausedException
import spock.adb.flutter.vmservice.VmServiceProbe
import spock.adb.flutter.vmservice.VmServiceRedirectException
import spock.adb.flutter.vmservice.VmServiceRpcException
import spock.adb.flutter.vmservice.VmServiceUri
import spock.adb.flutter.vmservice.string
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One connection to a running Flutter app's VM Service, and what Spock knows about the app from it.
 *
 * Every blocking call runs on the caller's thread — call [connect], [selectIsolate] and [close]
 * from a pooled thread, never the EDT. Work triggered by events (re-selecting the UI isolate after
 * a hot restart, reading a newly registered extension) runs on the session's own thread, a moment
 * after the event, so a burst of events costs one re-selection and the event thread never waits on
 * the app. Nothing here touches Swing.
 *
 * The session's lock guards only its fields: it is never held across a VM Service call or a
 * listener call. Selection work is serialised by a lock of its own, and [close] first stops every
 * call still waiting, so it never waits behind a stuck isolate.
 *
 * What answers is asked before anything else, not taken from where the address came from: a
 * device address forwarded by hand looks like a pasted DDS one. DDS serves
 * `getDartDevelopmentServiceVersion`; the VM itself does not. A VM with no DDS in front of it is
 * left at once unless the caller opts in, because a client that stays there keeps `flutter run`
 * and `flutter attach` from starting DDS (spike S10) [FR1].
 *
 * Side effects on the app are kept to one, and undone [FR10]:
 * - `structuredErrors` is only read, never set. Debug builds already have it on (spike S3), and
 *   switching it would change where the app reports its errors for every other tool.
 * - `httpEnableTimelineLogging` is off by default (spike S4) and P5 needs it for HTTP failures, so
 *   it is switched on for each new UI isolate — only on a [ConnectionKind.DDS] connection, in a
 *   debug or profile build, and when the caller's `recordHttp` allows — and off again on [close],
 *   only where Spock was the one that switched it on; one that was already on is never touched.
 *   [FlutterSessionSnapshot.httpRecording] says which, or why not. A direct connection kept by
 *   opt-in makes no writes at all. A direct address the VM hands to DDS on connect answers the
 *   probe as DDS, and is a DDS connection like any other.
 */
class FlutterSession(
    private val connector: (VmServiceUri) -> VmServiceClient = { VmServiceClient.connect(it) },
    /** Now, for [connectedAt]. Nothing is decided by it: see [EventHistory] for what history is. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val streams: List<String> = DEFAULT_STREAMS,
) : AutoCloseable {

    /** One [connect]: whoever ends it — [close], a lost connection, a failure — tears down this one. */
    private class Attempt(@Volatile var candidate: VmServiceCandidate, val recordHttp: Boolean) {
        /** Set under the session's lock, and only while this is the session's attempt. */
        @Volatile
        var client: VmServiceClient? = null

        /** What answered on [client]; null until asked. Only a DDS connection is restored on close. */
        @Volatile
        var kind: ConnectionKind? = null
    }

    private val listeners = CopyOnWriteArrayList<FlutterSessionListener>()
    private val notifier = StateNotifier(listeners)

    /** Guards the fields below and [snapshot]'s changes. Never held across a call or a listener. */
    private val lock = Any()

    /** Serialises selection and per-isolate set-up, which make VM Service calls. */
    private val selectionLock = ReentrantLock()

    @Volatile
    private var attempt: Attempt? = null
    private var httpLoggingCheckedFor: String? = null
    private var structuredErrorsReadFor: String? = null

    /** What Spock switched on, so [close] switches it off. */
    private val httpLogging = HttpLogging()

    /** One per session: a reconnect's replay of events already delivered is recognised. */
    private val history = EventHistory()

    private val reselectScheduled = AtomicBoolean()
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "Spock Flutter session").apply { isDaemon = true }
    }.apply {
        setKeepAliveTime(WORKER_IDLE_SECONDS, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    /** Everything below, as one consistent value. */
    @Volatile
    var snapshot: FlutterSessionSnapshot = FlutterSessionSnapshot(SessionState.Disconnected("not connected"))
        private set

    /** The raw connection, for the session and its tests; callers go through [callUiExtension] and [callVm]. */
    internal val client: VmServiceClient? get() = attempt?.client

    val state: SessionState get() = snapshot.state

    /** The last isolate selection; [IsolateSelection.Ambiguous] waits for [selectIsolate]. */
    val selection: IsolateSelection? get() = snapshot.selection

    val uiIsolate: FlutterIsolate? get() = snapshot.uiIsolate

    val uiIsolateId: String? get() = snapshot.uiIsolateId

    /** When the connection opened, on [clock]. */
    @Volatile
    var connectedAt: Long? = null
        private set

    /** The `getVM` answer read on connect. */
    @Volatile
    var vm: JsonObject? = null
        private set

    /**
     * The device's clock and zone, measured once for this session by [FlutterSessionService]
     * when it attaches on a device it knows (design §4a), and kept across reconnects. Diagnose
     * and the Timeline read device times through it. Empty for a session opened without a device.
     */
    val deviceTime = DeviceTimeSlot()

    /** Null until read, or when the UI isolate has no inspector (profile builds). */
    val structuredErrorsEnabled: Boolean? get() = snapshot.structuredErrorsEnabled

    /** What answered on connect; null while not connected. */
    val connectionKind: ConnectionKind? get() = snapshot.connectionKind

    /** Anything but DDS, including not connected (yet): watch, do not change anything in the app. */
    val readOnly: Boolean get() = snapshot.connectionKind != ConnectionKind.DDS

    /**
     * A hint from the UI isolate's extensions: the inspector is registered only in debug builds,
     * and the rest of `ext.flutter.*` in profile builds too. Release builds have no VM Service.
     */
    val buildMode: FlutterBuild? get() = uiIsolate?.let { buildModeOf(it.extensionRpcs) }

    /**
     * Registers [listener] once, however often it is added. With [replayState] it first hears the
     * state as it is now, then every change after it, none twice — for a listener that arrives
     * after [connect] and must not miss `Connected`. The catch-up is delivered on the session's
     * thread, never the caller's (which may be the EDT).
     */
    fun addListener(listener: FlutterSessionListener, replayState: Boolean = false) {
        if (!replayState) {
            listeners.addIfAbsent(listener)
            return
        }
        synchronized(lock) {
            listeners.addIfAbsent(listener)
            notifier.enqueueFor(listener, snapshot.state)
        }
        worker.execute { notifier.flush() }
    }

    fun removeListener(listener: FlutterSessionListener) {
        listeners -= listener
    }

    /**
     * Opens [candidate], asks what answers ([connectionKind]), reads the VM, subscribes to
     * [streams] and selects the UI isolate. Blocking. A [close] meanwhile ends it.
     *
     * @param allowDirect keep a connection to a VM with no DDS in front of it, read-only. It then
     * keeps `flutter run` and `flutter attach` from starting DDS until [close] — the developer's
     * explicit choice, never a default.
     * @param recordHttp switch HTTP timeline logging on where the connection and build allow it
     * (see [FlutterSessionSnapshot.httpRecording]); false leaves it alone.
     * @throws NoDdsException when the VM has no DDS in front of it and [allowDirect] is false: the
     * connection is closed before anything else is called, and before any listener hears of it.
     * @throws VmServiceException when the connection or its first calls fail; nothing is left open,
     * and the state is [SessionState.Disconnected] with the reason.
     */
    // Anything that escapes — the VM Service failing, or a bug — must not leave a socket open or
    // the session half set: it is cleaned up and rethrown as it was.
    @Suppress("TooGenericExceptionCaught")
    fun connect(
        candidate: VmServiceCandidate,
        allowDirect: Boolean = false,
        recordHttp: Boolean = true,
    ) {
        val attempt = Attempt(candidate, recordHttp)
        synchronized(lock) {
            check(this.attempt == null) { "This session is already connected; close it first." }
            this.attempt = attempt
            // What was read on a lost connection is stale. What httpLogging switched on is kept: the
            // app still has logging on, and close() must still switch it off.
            structuredErrorsReadFor = null
            httpLoggingCheckedFor = null
            snapshot = snapshot.withoutConnection().copy(selection = null, structuredErrorsEnabled = null)
        }
        try {
            val connected = openFollowingDds(attempt)
            hold(attempt, connected)
            val kind = VmServiceProbe.kindOf(connected)
            attempt.kind = kind
            if (kind == ConnectionKind.DIRECT_NO_DDS && !allowDirect) {
                // Spike S10: every moment held here can keep `flutter run`/`attach` from starting DDS.
                // Abandoned here, so the socket is closed before any listener hears of it.
                val refused = NoDdsException()
                abandon(attempt, refused.message.orEmpty())
                throw refused
            }
            synchronized(lock) {
                ensureCurrent(attempt)
                snapshot = snapshot.copy(connectionKind = kind)
            }
            val description = connected.getVM()
            synchronized(lock) {
                ensureCurrent(attempt)
                vm = description
                snapshot = snapshot.copy(
                    vmPid = description.number("pid")?.toInt(),
                    vmStartTimeMs = description.number("startTime")?.toLong(),
                    operatingSystem = description.string("operatingSystem"),
                )
                setState(SessionState.Connected(null))
            }
            notifier.flush()
            streams.forEach { listen(connected, it) }
            selectionLock.withLock { applySelection(connected, IsolateSelector(connected).select(), keepChoice = true) }
            notifier.flush()
            synchronized(lock) { ensureCurrent(attempt) }
        } catch (e: Exception) {
            abandon(attempt, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * Makes [isolateId] the UI isolate — the caller's answer to [IsolateSelection.Ambiguous].
     * Returns what happened: [IsolateSelection.Paused] when that isolate is paused.
     */
    fun selectIsolate(isolateId: String): IsolateSelection {
        val connected = client ?: return IsolateSelection.NoFlutterIsolate("Not connected.")
        val chosen = selectionLock.withLock {
            val isolate = IsolateSelector(connected).describe(isolateId)
                ?: return IsolateSelection.NoFlutterIsolate("Isolate $isolateId is gone.")
            IsolateSelector.decide(listOf(isolate)).also { applySelection(connected, it, keepChoice = false) }
        }
        notifier.flush()
        return chosen
    }

    /**
     * Calls the service extension [method] on the UI isolate, for a read: what Diagnose and the
     * timeline ask the app. The result comes scrubbed of VM Service addresses [FR9]. Blocking.
     *
     * Nothing is sent to a paused isolate, which would not answer; an isolate that pauses during
     * the call costs [timeoutMs]. On a read-only connection only known reads go through (the HTTP
     * profile, and the HTTP-logging and structured-errors flags asked with no arguments); anything
     * else — `ext.flutter.exit`, `reassemble`, any call with `enabled` — is refused.
     *
     * @throws VmServiceClosedException when not connected, or the connection ends meanwhile.
     * @throws VmServicePausedException when the UI isolate is paused in the debugger [FR12].
     * @throws NoUiIsolateException when no UI isolate is chosen ([selection] says why).
     * @throws ReadOnlyConnectionException for anything but a known read on a read-only connection.
     */
    fun callUiExtension(method: String, params: Map<String, String> = emptyMap(), timeoutMs: Long? = null): JsonObject {
        val (connected, isolateId) = synchronized(lock) {
            val connected = client
            uiCallProblem(snapshot, connected, method, params)?.let { throw it }
            checkNotNull(connected) to checkNotNull(snapshot.uiIsolateId)
        }
        val result = connected.callServiceExtension(method, isolateId, params, timeoutMs ?: connected.defaultTimeoutMs)
        return Redaction.scrubJson(result).asJsonObject
    }

    /**
     * Calls a VM-level RPC for a read — `getVM`, `getIsolate`, `_flutter.listViews`,
     * `_flutter.getDisplayRefreshRate`, `getVMTimelineMicros`. The result comes scrubbed [FR9].
     * Blocking. Service extensions go through [callUiExtension]; `getStreamHistory` is not served,
     * as its stdout bytes can carry the token.
     *
     * @throws IllegalArgumentException for an `ext.*` method or `getStreamHistory`.
     * @throws VmServiceClosedException when not connected, or the connection ends meanwhile.
     * @throws ReadOnlyConnectionException for a known mutator (`resume`, `evaluate…`, `set…`,
     * `kill`…) on a read-only connection.
     */
    fun callVm(method: String, params: JsonObject = JsonObject(), timeoutMs: Long? = null): JsonObject {
        require(!method.startsWith(EXTENSION_PREFIX)) { "$method is a service extension: use callUiExtension." }
        require(method != STREAM_HISTORY) { "$method is not served: its stdout bytes can carry the token." }
        val connected = synchronized(lock) {
            val connected = client ?: throw VmServiceClosedException("not connected")
            if (readOnly && ReadOnlyCalls.isVmMutator(method)) throw ReadOnlyConnectionException(method)
            connected
        }
        val result = connected.call(method, params, timeoutMs ?: connected.defaultTimeoutMs)
        return Redaction.scrubExtensionResult(method, Redaction.scrubJson(result).asJsonObject)
    }

    /**
     * Switches HTTP timeline logging back off where Spock switched it on, closes the connection
     * and releases what discovery set up (an `adb forward`). Blocking but brief: calls still
     * waiting — a stuck re-selection, a connect — are failed first. Safe to repeat.
     */
    override fun close() {
        val closed = synchronized(lock) {
            attempt?.also { detach(SessionState.Disconnected(CLOSED_BY_SPOCK)) }
        }
        // Listeners hear Disconnected once the socket is closed, not while it is (spike S10).
        // An attempt with no client yet is still opening its socket; connect ends it itself.
        try {
            if (closed?.client != null) tearDown(closed)
        } finally {
            notifier.flush()
        }
    }

    /**
     * Once DDS owns the VM, the VM redirects a client of its own address to DDS — spike S9's
     * "a direct client gets DDS" is dart:io following that redirect. The JDK's client does not
     * follow it, so it is followed here, once; the connection is then DDS's, not direct.
     */
    private fun openFollowingDds(attempt: Attempt): VmServiceClient = try {
        connector(attempt.candidate.open())
    } catch (e: VmServiceRedirectException) {
        val handed = HandedToDdsCandidate(attempt.candidate, e.target)
        attempt.candidate = handed
        connector(handed.open())
    }

    /** Makes [connected] the attempt's client — unless [close] came first, which ends it here. */
    private fun hold(attempt: Attempt, connected: VmServiceClient) {
        val held = synchronized(lock) {
            if (this.attempt === attempt) {
                attempt.client = connected
                val now = clock()
                connectedAt = now
                snapshot = snapshot.copy(connectedAtHostMs = now)
                history.newConnection()
            }
            this.attempt === attempt
        }
        if (!held) {
            connected.close()
            throw VmServiceClosedException(CLOSED_BY_SPOCK)
        }
        connected.addListener(ConnectionListener(connected))
    }

    /** Under the lock. */
    private fun ensureCurrent(attempt: Attempt) {
        if (this.attempt !== attempt) throw VmServiceClosedException(CLOSED_BY_SPOCK)
    }

    /** A connect that failed: ends the attempt, unless [close] or a lost connection already did. */
    private fun abandon(attempt: Attempt, reason: String) {
        val ours = synchronized(lock) {
            when {
                this.attempt === attempt -> {
                    detach(SessionState.Disconnected(reason))
                    true
                }
                // Closed before it had a client to hand over: the clean-up is the connect's.
                else -> attempt.client == null
            }
        }
        try {
            if (ours) tearDown(attempt)
        } finally {
            notifier.flush()
        }
    }

    /** Under the lock: the session lets go of its attempt and says why. */
    private fun detach(state: SessionState.Disconnected) {
        attempt = null
        snapshot = snapshot.withoutConnection()
        setState(state)
    }

    /** Restores what Spock changed, then closes and releases — each step whatever the last did. */
    private fun tearDown(attempt: Attempt) {
        val connected = attempt.client
        try {
            if (connected != null && connected.isOpen) {
                connected.failPendingCalls("the Flutter session is closing")
                // Only through DDS: a direct connection writes nothing, and the isolates stay listed
                // for a later DDS one. A connect closed before its probe answered asks now — only
                // when there is something to switch off.
                val kind = attempt.kind ?: if (httpLogging.owesRestore()) kindOnClose(connected) else null
                if (kind == ConnectionKind.DDS) httpLogging.restore(connected)
            }
        } finally {
            try {
                connected?.close()
            } finally {
                release(attempt.candidate)
            }
        }
    }

    /** Re-selects a moment from now, once for any number of requests until it runs. */
    private fun reselectSoon() {
        if (!reselectScheduled.compareAndSet(false, true)) return
        later(RESELECT_DELAY_MS, "re-select the UI isolate") {
            reselectScheduled.set(false)
            val connected = client ?: return@later
            selectionLock.withLock { applySelection(connected, IsolateSelector(connected).select(), keepChoice = true) }
        }
    }

    private fun setUpSoon(isolateId: String) {
        later(0, "set up $isolateId") {
            val connected = client ?: return@later
            selectionLock.withLock { setUp(connected, isolateId) }
        }
    }

    // Work on the session thread: a failure is logged, and the next event tries again.
    @Suppress("TooGenericExceptionCaught")
    private fun later(delayMs: Long, what: String, task: () -> Unit) {
        val guarded = Runnable {
            try {
                task()
            } catch (e: VmServiceException) {
                log.warn("Could not $what: ${e.message}")
            } catch (e: Exception) {
                log.warn("Could not $what", e)
            }
            notifier.flush()
        }
        worker.schedule(guarded, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Under [selectionLock]. Adopting an isolate sets it up before the state says Connected. */
    private fun applySelection(connected: VmServiceClient, result: IsolateSelection, keepChoice: Boolean) {
        val adopted = synchronized(lock) {
            if (client !== connected) return
            val current = snapshot.uiIsolate
            // A choice the caller made among several stays made while it is still one of them.
            if (keepChoice && result is IsolateSelection.Ambiguous && result.candidates.any { it.id == current?.id }) {
                snapshot = snapshot.copy(selection = result)
                return
            }
            when (result) {
                is IsolateSelection.Selected -> {
                    val kept = snapshot.structuredErrorsEnabled.takeIf { current?.id == result.isolate.id }
                    snapshot = snapshot.copy(
                        selection = result,
                        uiIsolate = result.isolate,
                        structuredErrorsEnabled = kept,
                        httpRecording = snapshot.httpRecording.takeIf { httpLoggingCheckedFor == result.isolate.id },
                    )
                    result.isolate
                }
                is IsolateSelection.Paused -> {
                    // No UI isolate: nothing to report, and the next one is decided afresh.
                    httpLoggingCheckedFor = null
                    snapshot = snapshot.copy(selection = result, uiIsolate = null, httpRecording = null)
                    setState(SessionState.Paused(result.isolate.id, result.isolate.pauseKind.orEmpty()))
                    null
                }
                is IsolateSelection.Ambiguous, is IsolateSelection.NoFlutterIsolate -> {
                    httpLoggingCheckedFor = null
                    snapshot = snapshot.copy(selection = result, uiIsolate = null, httpRecording = null)
                    setState(SessionState.Connected(null))
                    null
                }
            }
        } ?: return
        // Whoever acts on Connected — Diagnose reading structuredErrorsEnabled — must find the
        // session ready, not half read.
        setUp(connected, adopted.id)
        synchronized(lock) {
            val ui = snapshot.uiIsolate
            if (client === connected && ui?.id == adopted.id && !ui.paused) setState(SessionState.Connected(ui.id))
        }
    }

    /**
     * Under [selectionLock]. Per-isolate work, each step once per isolate: extensions register a
     * few at a time. Nothing is called on an isolate the session knows is paused [FR12].
     */
    private fun setUp(connected: VmServiceClient, isolateId: String) {
        val (readErrors, enableLogging) = synchronized(lock) {
            val isolate = snapshot.uiIsolate?.takeIf { it.id == isolateId && client === connected } ?: return
            // Its pause kind is the latest: from getIsolate on selection, then each Pause/Resume event.
            if (isolate.paused) return
            val rpcs = isolate.extensionRpcs
            val readErrors = FlutterIsolate.STRUCTURED_ERRORS in rpcs && structuredErrorsReadFor != isolateId
            if (readErrors) structuredErrorsReadFor = isolateId
            readErrors to (httpLoggingCheckedFor != isolateId && decideHttp(isolate))
        }
        if (readErrors) {
            val enabled = readBool(connected, FlutterIsolate.STRUCTURED_ERRORS, isolateId)
            synchronized(lock) {
                if (client === connected && snapshot.uiIsolate?.id == isolateId) {
                    snapshot = snapshot.copy(structuredErrorsEnabled = enabled)
                }
            }
        }
        if (enableLogging) enableHttpLogging(connected, isolateId)
    }

    /** Under [selectionLock]: switches logging on unless the connection stopped allowing it meanwhile. */
    private fun enableHttpLogging(connected: VmServiceClient, isolateId: String) {
        val outcome = httpLogging.enable(connected, isolateId) {
            synchronized(lock) { client === connected && !readOnly }
        } ?: return
        synchronized(lock) {
            if (client === connected && snapshot.uiIsolate?.id == isolateId) {
                snapshot = snapshot.copy(httpRecording = outcome.recording)
                // An answer not known is asked again at the isolate's next extension or resume.
                if (outcome.retry && httpLoggingCheckedFor == isolateId) httpLoggingCheckedFor = null
            }
        }
    }

    /**
     * Under the lock: whether to switch HTTP logging on for [isolate] now. When not, the snapshot
     * says why; a build mode not known yet is decided again as the isolate's extensions register.
     */
    private fun decideHttp(isolate: FlutterIsolate): Boolean {
        val rpcs = isolate.extensionRpcs
        val reason = when {
            attempt?.recordHttp != true -> HttpRecording.Reason.SETTING_OFF
            readOnly -> HttpRecording.Reason.NOT_DDS
            buildModeOf(rpcs) == null -> HttpRecording.Reason.RELEASE_OR_UNKNOWN_MODE
            else -> null
        }
        if (reason != null) {
            snapshot = snapshot.copy(httpRecording = HttpRecording.Off(reason))
            if (reason != HttpRecording.Reason.RELEASE_OR_UNKNOWN_MODE) httpLoggingCheckedFor = isolate.id
        } else if (HTTP_LOGGING !in rpcs) {
            snapshot = snapshot.copy(httpRecording = HttpRecording.Pending)
        }
        val enable = reason == null && HTTP_LOGGING in rpcs
        if (enable) httpLoggingCheckedFor = isolate.id
        return enable
    }

    /** Under the lock; listeners hear of it at the next [StateNotifier.flush]. */
    private fun setState(newState: SessionState) {
        if (snapshot.state == newState) return
        snapshot = snapshot.copy(state = newState)
        notifier.enqueue(newState)
    }

    /**
     * On the client's event thread. History never changes state: it is what was, not what is.
     * Only decisions here; the calls they need run later on the session thread.
     */
    private fun onLiveEvent(owner: VmServiceClient, event: VmServiceEvent) {
        synchronized(lock) { if (client === owner) decide(event) }
    }

    /** Under the lock. */
    private fun decide(event: VmServiceEvent) {
        val ui = snapshot.uiIsolate
        val ownIsolate = ui != null && event.isolateId == ui.id
        // Several Flutter isolates and no choice made yet: only the caller's choice moves on.
        val waitingForChoice = ui == null && snapshot.selection is IsolateSelection.Ambiguous
        val kind = event.kind
        when {
            // With a UI isolate in hand, a start is a background isolate (`compute()` spawns them
            // all the time); a hot restart's new isolate is picked up when the old one exits.
            kind == "IsolateStart" -> if (ui == null && !waitingForChoice) reselectSoon()
            kind == "IsolateExit" -> onIsolateExit(event, ui == null || ownIsolate)
            kind == "ServiceExtensionAdded" -> onExtensionAdded(event, ui, ownIsolate, waitingForChoice)
            ui == null -> if (kind == "Resume" && !waitingForChoice) reselectSoon()
            ownIsolate -> onUiIsolateEvent(ui, event)
        }
    }

    /** Under the lock: the UI isolate paused, resumed, or reported an extension event. */
    private fun onUiIsolateEvent(ui: FlutterIsolate, event: VmServiceEvent) {
        val kind = event.kind
        when {
            kind == "Resume" -> {
                snapshot = snapshot.copy(uiIsolate = ui.copy(pauseKind = kind))
                setState(SessionState.Connected(ui.id))
                // Extensions that registered while it was paused were not set up.
                setUpSoon(ui.id)
            }
            kind in FlutterIsolate.PAUSED_KINDS -> {
                snapshot = snapshot.copy(uiIsolate = ui.copy(pauseKind = kind))
                setState(SessionState.Paused(ui.id, kind.orEmpty()))
            }
            kind == "Extension" -> onExtensionEvent(event)
        }
    }

    private fun onIsolateExit(event: VmServiceEvent, affectsSelection: Boolean) {
        event.isolateId?.let(httpLogging::forget)
        if (affectsSelection) reselectSoon()
    }

    private fun onExtensionAdded(event: VmServiceEvent, ui: FlutterIsolate?, ownIsolate: Boolean, waiting: Boolean) {
        val rpc = event.event.string("extensionRPC")
        when {
            ui == null -> if (!waiting) reselectSoon()
            ownIsolate && rpc != null -> {
                snapshot = snapshot.copy(uiIsolate = ui.withExtension(rpc))
                setUpSoon(ui.id)
            }
        }
    }

    /** Keeps [structuredErrorsEnabled] in step when another tool (DevTools, the IDE) toggles it. */
    private fun onExtensionEvent(event: VmServiceEvent) {
        if (event.extensionKind != STATE_CHANGED) return
        val data = event.event.get("extensionData") as? JsonObject ?: return
        if (data.string("extension") != FlutterIsolate.STRUCTURED_ERRORS) return
        ExtensionResults.bool(data, "value")?.let { snapshot = snapshot.copy(structuredErrorsEnabled = it) }
    }

    /** Bound to one client, so a late close or event from a previous connection is ignored. */
    private inner class ConnectionListener(private val owner: VmServiceClient) : VmServiceListener {
        override fun onEvent(event: VmServiceEvent) {
            if (client !== owner) return
            val accepted = history.accept(event) ?: return
            if (!accepted.history) onLiveEvent(owner, event)
            notifier.flush()
            listeners.forEach { deliver(it, accepted) }
        }

        override fun onClosed(reason: String) {
            val lost = synchronized(lock) {
                if (client !== owner) return
                attempt?.also { detach(SessionState.Disconnected(reason)) }
            }
            notifier.flush()
            lost?.let(::tearDown)
        }

        // A listener's bug must cost neither the other listeners nor the session this event.
        @Suppress("TooGenericExceptionCaught")
        private fun deliver(listener: FlutterSessionListener, event: FlutterEvent) {
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                log.warn("Flutter session listener failed on $event", e)
            }
        }
    }

    companion object {
        /**
         * `Isolate` and `Debug` follow restarts and pauses; `Extension` carries `Flutter.*` events;
         * `Logging` carries `dart:developer` logs.
         */
        val DEFAULT_STREAMS = listOf("Isolate", "Debug", "Extension", "Logging")

        const val HTTP_LOGGING = HttpLogging.HTTP_LOGGING

        private const val EXTENSION_PREFIX = "ext."
        private const val STREAM_HISTORY = "getStreamHistory"

        /** Closing should be quick: a connection that cannot say what it is in this restores nothing. */
        internal const val PROBE_ON_CLOSE_MS = 2_000L
        const val CLOSED_BY_SPOCK = "closed by Spock"
        private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"

        /** A hot restart registers dozens of extensions within this; they cost one re-selection. */
        private const val RESELECT_DELAY_MS = 100L

        private const val WORKER_IDLE_SECONDS = 5L
    }
}

private val log = Logger.getInstance(FlutterSession::class.java)

// A failed release (adb gone) must not stop the session from ending.
@Suppress("TooGenericExceptionCaught")
private fun release(candidate: VmServiceCandidate) {
    try {
        candidate.release()
    } catch (e: Exception) {
        log.warn("Could not release ${candidate.description}", e)
    }
}

private fun listen(connected: VmServiceClient, streamId: String) {
    try {
        connected.streamListen(streamId)
    } catch (e: VmServiceRpcException) {
        if (e.code != VmServiceRpcException.STREAM_ALREADY_SUBSCRIBED) {
            log.warn("Could not listen to $streamId: ${e.message}")
        }
    }
}

/** Why [method] cannot be sent to the UI isolate now, or null when it can. */
private fun uiCallProblem(
    snapshot: FlutterSessionSnapshot,
    connected: VmServiceClient?,
    method: String,
    params: Map<String, String>,
): VmServiceException? {
    val state = snapshot.state
    val ui = snapshot.uiIsolate
    return when {
        connected == null -> VmServiceClosedException("not connected")
        state is SessionState.Paused -> VmServicePausedException(state.isolateId, state.pauseKind)
        ui == null -> NoUiIsolateException(noUiIsolate(snapshot.selection))
        ui.paused -> VmServicePausedException(ui.id, ui.pauseKind.orEmpty())
        snapshot.connectionKind != ConnectionKind.DDS && !ReadOnlyCalls.isReadExtension(method, params) ->
            ReadOnlyConnectionException(method)
        else -> null
    }
}

/** What [connected] is, asked while closing; null when it cannot say. */
private fun kindOnClose(connected: VmServiceClient): ConnectionKind? = try {
    VmServiceProbe.kindOf(connected, FlutterSession.PROBE_ON_CLOSE_MS)
} catch (e: VmServiceException) {
    log.warn("Could not tell DDS from the VM on close; HTTP logging stays as it is: ${e.message}")
    null
}

/** Why there is no UI isolate to call, for a [NoUiIsolateException]. */
private fun noUiIsolate(selection: IsolateSelection?): String = when (selection) {
    is IsolateSelection.Ambiguous -> "Several Flutter isolates are running; choose one first."
    is IsolateSelection.NoFlutterIsolate -> selection.reason
    is IsolateSelection.Paused -> selection.message
    is IsolateSelection.Selected, null -> "No Flutter isolate is selected yet."
}

/**
 * A hint from an isolate's extensions: the inspector is registered only in debug builds, and the
 * rest of `ext.flutter.*` in profile builds too. Release builds have no VM Service.
 */
private fun buildModeOf(rpcs: Set<String>): FlutterBuild? = when {
    rpcs.any { it.startsWith(FlutterIsolate.INSPECTOR_PREFIX) } -> FlutterBuild.DEBUG
    rpcs.any { it.startsWith(FlutterIsolate.FLUTTER_EXTENSION_PREFIX) } -> FlutterBuild.PROFILE
    else -> null
}

private fun JsonObject.number(key: String): Number? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.asNumber
