package spock.adb.flutter

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.HandedToDdsCandidate
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServiceEvent
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceListener
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
 * Side effects on the app are kept to one, and undone [FR10]:
 * - `structuredErrors` is only read, never set. Debug builds already have it on (spike S3), and
 *   switching it would change where the app reports its errors for every other tool.
 * - `httpEnableTimelineLogging` is off by default (spike S4) and P5 needs it for HTTP failures, so
 *   it is switched on for each new UI isolate — and off again on [close], only where Spock was the
 *   one that switched it on. A [VmServiceCandidate.direct] connection makes no writes at all —
 *   unless the VM hands it to DDS on connect, which makes it a DDS connection like any other.
 */
class FlutterSession(
    private val connector: (VmServiceUri) -> VmServiceClient = { VmServiceClient.connect(it) },
    /** Now, for [connectedAt]. Nothing is decided by it: see [EventHistory] for what history is. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val streams: List<String> = DEFAULT_STREAMS,
) : AutoCloseable {

    /** One [connect]: whoever ends it — [close], a lost connection, a failure — tears down this one. */
    private class Attempt(@Volatile var candidate: VmServiceCandidate) {
        /** Set under the session's lock, and only while this is the session's attempt. */
        @Volatile
        var client: VmServiceClient? = null
    }

    private val listeners = CopyOnWriteArrayList<FlutterSessionListener>()
    private val notifier = StateNotifier(listeners)

    /** Guards the fields below and [snapshot]'s changes. Never held across a call or a listener. */
    private val lock = Any()

    /** Serialises selection and per-isolate set-up, which make VM Service calls. */
    private val selectionLock = ReentrantLock()

    /** Orders switching HTTP logging on against switching it back off. */
    private val writeLock = ReentrantLock()

    @Volatile
    private var attempt: Attempt? = null
    private var httpLoggingCheckedFor: String? = null
    private var structuredErrorsReadFor: String? = null

    /** UI isolates where Spock switched HTTP timeline logging on, so [close] switches it off. */
    private val httpLoggingEnabledBySpock = mutableSetOf<String>()

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

    val client: VmServiceClient? get() = attempt?.client

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

    /** Null until read, or when the UI isolate has no inspector (profile builds). */
    val structuredErrorsEnabled: Boolean? get() = snapshot.structuredErrorsEnabled

    /** A direct device URI: watch, do not change anything in the app. */
    val readOnly: Boolean get() = attempt?.candidate?.direct == true

    /**
     * A hint from the UI isolate's extensions: the inspector is registered only in debug builds,
     * and the rest of `ext.flutter.*` in profile builds too. Release builds have no VM Service.
     */
    val buildMode: FlutterBuild?
        get() {
            val rpcs = uiIsolate?.extensionRpcs ?: return null
            return when {
                rpcs.any { it.startsWith(FlutterIsolate.INSPECTOR_PREFIX) } -> FlutterBuild.DEBUG
                rpcs.any { it.startsWith(FlutterIsolate.FLUTTER_EXTENSION_PREFIX) } -> FlutterBuild.PROFILE
                else -> null
            }
        }

    fun addListener(listener: FlutterSessionListener) {
        listeners += listener
    }

    fun removeListener(listener: FlutterSessionListener) {
        listeners -= listener
    }

    /**
     * Opens [candidate], reads the VM, subscribes to [streams] and selects the UI isolate. Returns
     * the client for calls the session does not wrap. Blocking. A [close] meanwhile ends it.
     *
     * @throws VmServiceException when the connection or its first calls fail; nothing is left open,
     * and the state is [SessionState.Disconnected] with the reason.
     */
    // Anything that escapes — the VM Service failing, or a bug — must not leave a socket open or
    // the session half set: it is cleaned up and rethrown as it was.
    @Suppress("TooGenericExceptionCaught")
    fun connect(candidate: VmServiceCandidate): VmServiceClient {
        val attempt = Attempt(candidate)
        synchronized(lock) {
            check(this.attempt == null) { "This session is already connected; close it first." }
            this.attempt = attempt
            // What was read on a lost connection is stale. httpLoggingEnabledBySpock is kept: the
            // app still has logging on, and close() must still switch it off.
            structuredErrorsReadFor = null
            httpLoggingCheckedFor = null
            snapshot = snapshot.copy(selection = null, uiIsolate = null, structuredErrorsEnabled = null)
        }
        try {
            val connected = openFollowingDds(attempt)
            hold(attempt, connected)
            val description = connected.getVM()
            synchronized(lock) {
                ensureCurrent(attempt)
                vm = description
                setState(SessionState.Connected(null))
            }
            notifier.flush()
            streams.forEach { listen(connected, it) }
            selectionLock.withLock { applySelection(connected, IsolateSelector(connected).select(), keepChoice = true) }
            notifier.flush()
            synchronized(lock) { ensureCurrent(attempt) }
            return connected
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
     * Switches HTTP timeline logging back off where Spock switched it on, closes the connection
     * and releases what discovery set up (an `adb forward`). Blocking but brief: calls still
     * waiting — a stuck re-selection, a connect — are failed first. Safe to repeat.
     */
    override fun close() {
        val closed = synchronized(lock) {
            attempt?.also { detach(SessionState.Disconnected(CLOSED_BY_SPOCK)) }
        }
        notifier.flush()
        // An attempt with no client yet is still opening its socket; connect ends it itself.
        if (closed?.client != null) tearDown(closed)
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
                connectedAt = clock()
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
        notifier.flush()
        if (ours) tearDown(attempt)
    }

    /** Under the lock: the session lets go of its attempt and says why. */
    private fun detach(state: SessionState.Disconnected) {
        attempt = null
        snapshot = snapshot.copy(uiIsolate = null)
        setState(state)
    }

    /** Restores what Spock changed, then closes and releases — each step whatever the last did. */
    private fun tearDown(attempt: Attempt) {
        val connected = attempt.client
        try {
            if (connected != null && connected.isOpen) {
                connected.failPendingCalls("the Flutter session is closing")
                // A direct connection writes nothing; the isolates stay listed for a later DDS one.
                if (!attempt.candidate.direct) restoreHttpLogging(connected)
            }
        } finally {
            try {
                connected?.close()
            } finally {
                release(attempt.candidate)
            }
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
                    )
                    result.isolate
                }
                is IsolateSelection.Paused -> {
                    snapshot = snapshot.copy(selection = result, uiIsolate = null)
                    setState(SessionState.Paused(result.isolate.id, result.isolate.pauseKind.orEmpty()))
                    null
                }
                is IsolateSelection.Ambiguous, is IsolateSelection.NoFlutterIsolate -> {
                    snapshot = snapshot.copy(selection = result, uiIsolate = null)
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
            val enableLogging = !readOnly && HTTP_LOGGING in rpcs && httpLoggingCheckedFor != isolateId
            if (enableLogging) httpLoggingCheckedFor = isolateId
            readErrors to enableLogging
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

    /**
     * The isolate is recorded before the write: a write that times out may still be applied, and
     * switching it off on close is harmless if it was not. Only a refusal un-records it.
     */
    private fun enableHttpLogging(connected: VmServiceClient, isolateId: String) {
        if (readBool(connected, HTTP_LOGGING, isolateId) != false) return
        writeLock.withLock {
            synchronized(lock) {
                if (client !== connected || readOnly) return
                httpLoggingEnabledBySpock += isolateId
            }
            try {
                connected.callServiceExtension(HTTP_LOGGING, isolateId, mapOf("enabled" to "true"))
            } catch (e: VmServiceRpcException) {
                synchronized(lock) { httpLoggingEnabledBySpock -= isolateId }
                log.warn("Could not enable HTTP timeline logging: ${e.message}")
            } catch (e: VmServiceException) {
                log.warn("HTTP timeline logging may be on without an answer; close() switches it off: ${e.message}")
            }
        }
    }

    /** Every isolate Spock switched on that is still alive; a paused one is left, as it would not answer. */
    private fun restoreHttpLogging(connected: VmServiceClient) = writeLock.withLock {
        val isolateIds = synchronized(lock) {
            httpLoggingEnabledBySpock.toList().also { httpLoggingEnabledBySpock.clear() }
        }
        isolateIds.forEach { isolateId ->
            try {
                val params = JsonObject().apply { addProperty("isolateId", isolateId) }
                val isolate = FlutterIsolate.from(connected.call("getIsolate", params, RESTORE_TIMEOUT_MS))
                if (isolate != null && !isolate.paused) {
                    val off = mapOf("enabled" to "false")
                    connected.callServiceExtension(HTTP_LOGGING, isolateId, off, RESTORE_TIMEOUT_MS)
                }
            } catch (e: VmServiceException) {
                log.warn("Could not switch HTTP timeline logging back off: ${e.message}")
            }
        }
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
        event.isolateId?.let { httpLoggingEnabledBySpock -= it }
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

        const val HTTP_LOGGING = "ext.dart.io.httpEnableTimelineLogging"
        const val CLOSED_BY_SPOCK = "closed by Spock"
        private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"

        /** A hot restart registers dozens of extensions within this; they cost one re-selection. */
        private const val RESELECT_DELAY_MS = 100L

        /** Closing should be quick: an isolate that does not answer in this keeps its logging. */
        private const val RESTORE_TIMEOUT_MS = 2_000L
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

/** Called with no `enabled` argument, a bool extension only reports its value. */
private fun readBool(connected: VmServiceClient, extension: String, isolateId: String): Boolean? = try {
    ExtensionResults.bool(connected.callServiceExtension(extension, isolateId))
} catch (e: VmServiceException) {
    log.warn("Could not read $extension: ${e.message}")
    null
}
