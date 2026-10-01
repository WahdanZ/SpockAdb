package spock.adb.flutter

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.HandedToDdsCandidate
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceEvent
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceListener
import spock.adb.flutter.vmservice.VmServiceRedirectException
import spock.adb.flutter.vmservice.VmServiceRpcException
import spock.adb.flutter.vmservice.VmServiceUri
import spock.adb.flutter.vmservice.string
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One connection to a running Flutter app's VM Service, and what Spock knows about the app from it.
 *
 * Every blocking call runs on the caller's thread — call [connect], [selectIsolate] and [close]
 * from a pooled thread, never the EDT. Work triggered by events (re-selecting the UI isolate after
 * a hot restart) runs on the client's event thread. Nothing here touches Swing.
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
    /**
     * Now, on the app's clock: event timestamps older than the connection are history. Host time
     * suits an iOS simulator; on an Android device pass a clock corrected for the device's drift.
     */
    private val clock: () -> Long = System::currentTimeMillis,
    private val streams: List<String> = DEFAULT_STREAMS,
) : AutoCloseable {

    private val listeners = CopyOnWriteArrayList<FlutterSessionListener>()

    @Volatile
    private var candidate: VmServiceCandidate? = null

    @Volatile
    private var history: EventHistory? = null
    private var httpLoggingCheckedFor: String? = null
    private var structuredErrorsReadFor: String? = null

    /** UI isolates where Spock switched HTTP timeline logging on, so [close] switches it off. */
    private val httpLoggingEnabledBySpock = mutableSetOf<String>()

    @Volatile
    var client: VmServiceClient? = null
        private set

    @Volatile
    var state: SessionState = SessionState.Disconnected("not connected")
        private set

    /** The last isolate selection; [IsolateSelection.Ambiguous] waits for [selectIsolate]. */
    @Volatile
    var selection: IsolateSelection? = null
        private set

    @Volatile
    var uiIsolate: FlutterIsolate? = null
        private set

    val uiIsolateId: String? get() = uiIsolate?.id

    /** When the connection opened, on [clock]. */
    @Volatile
    var connectedAt: Long? = null
        private set

    /** The `getVM` answer read on connect. */
    @Volatile
    var vm: JsonObject? = null
        private set

    /** Null until read, or when the UI isolate has no inspector (profile builds). */
    @Volatile
    var structuredErrorsEnabled: Boolean? = null
        private set

    /** A direct device URI: watch, do not change anything in the app. */
    val readOnly: Boolean get() = candidate?.direct == true

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
     * the client for calls the session does not wrap. Blocking.
     *
     * @throws VmServiceException when the connection or its first calls fail; nothing is left open.
     */
    @Synchronized
    fun connect(candidate: VmServiceCandidate): VmServiceClient {
        check(client == null) { "This session is already connected; close it first." }
        // What was read on a lost connection is stale. httpLoggingEnabledBySpock is kept: the app
        // still has logging on, and close() must still switch it off.
        selection = null
        structuredErrorsEnabled = null
        structuredErrorsReadFor = null
        httpLoggingCheckedFor = null
        this.candidate = candidate
        val connected = try {
            openFollowingDds(candidate)
        } catch (e: VmServiceException) {
            close()
            throw e
        }
        try {
            val startedAt = clock()
            connectedAt = startedAt
            history = EventHistory(startedAt)
            client = connected
            connected.addListener(ConnectionListener(connected))
            vm = connected.getVM()
            setState(SessionState.Connected(null))
            streams.forEach { listen(connected, it) }
            reselect()
        } catch (e: VmServiceException) {
            close()
            throw e
        }
        return connected
    }

    /**
     * Makes [isolateId] the UI isolate — the caller's answer to [IsolateSelection.Ambiguous].
     * Returns what happened: [IsolateSelection.Paused] when that isolate is paused.
     */
    @Synchronized
    fun selectIsolate(isolateId: String): IsolateSelection {
        val connected = client ?: return IsolateSelection.NoFlutterIsolate("Not connected.")
        val isolate = IsolateSelector(connected).describe(isolateId)
            ?: return IsolateSelection.NoFlutterIsolate("Isolate $isolateId is gone.")
        val chosen = IsolateSelector.decide(listOf(isolate))
        apply(chosen)
        return chosen
    }

    /**
     * Switches HTTP timeline logging back off where Spock switched it on, closes the connection
     * and releases what discovery set up (an `adb forward`). Blocking; safe to repeat.
     */
    @Synchronized
    override fun close() {
        val connected = client
        client = null
        if (connected != null) {
            restoreHttpLogging(connected)
            connected.close()
        }
        candidate?.release()
        candidate = null
        uiIsolate = null
        if (state !is SessionState.Disconnected) setState(SessionState.Disconnected("closed by Spock"))
    }

    /**
     * Once DDS owns the VM, the VM redirects a client of its own address to DDS — spike S9's
     * "a direct client gets DDS" is dart:io following that redirect. The JDK's client does not
     * follow it, so it is followed here, once; the connection is then DDS's, not direct.
     */
    private fun openFollowingDds(candidate: VmServiceCandidate): VmServiceClient = try {
        connector(candidate.open())
    } catch (e: VmServiceRedirectException) {
        val handed = HandedToDdsCandidate(candidate, e.target)
        this.candidate = handed
        connector(handed.open())
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

    @Synchronized
    private fun reselect() {
        val connected = client ?: return
        val result = IsolateSelector(connected).select()
        val current = uiIsolate
        // A choice the caller made among several stays made while it is still one of them.
        if (result is IsolateSelection.Ambiguous && current != null && result.candidates.any { it.id == current.id }) {
            selection = result
            return
        }
        apply(result)
    }

    private fun apply(result: IsolateSelection) {
        selection = result
        when (result) {
            is IsolateSelection.Selected -> adopt(result.isolate)
            is IsolateSelection.Paused -> {
                uiIsolate = null
                setState(SessionState.Paused(result.isolate.id, result.isolate.pauseKind.orEmpty()))
            }
            is IsolateSelection.Ambiguous, is IsolateSelection.NoFlutterIsolate -> {
                uiIsolate = null
                setState(SessionState.Connected(null))
            }
        }
    }

    /**
     * The isolate's set-up comes before the state says Connected: whoever acts on that state —
     * Diagnose reading [structuredErrorsEnabled] — must find the session ready, not half read.
     */
    private fun adopt(isolate: FlutterIsolate) {
        if (uiIsolate?.id != isolate.id) structuredErrorsEnabled = null
        uiIsolate = isolate
        setUp(isolate)
        setState(SessionState.Connected(isolate.id))
    }

    /** Per-isolate work, each step once per isolate: extensions register a few at a time. */
    private fun setUp(isolate: FlutterIsolate) {
        val connected = client ?: return
        if (isolate.paused) return
        if (FlutterIsolate.STRUCTURED_ERRORS in isolate.extensionRpcs && structuredErrorsReadFor != isolate.id) {
            structuredErrorsReadFor = isolate.id
            structuredErrorsEnabled = readBool(connected, FlutterIsolate.STRUCTURED_ERRORS, isolate.id)
        }
        if (!readOnly && HTTP_LOGGING in isolate.extensionRpcs && httpLoggingCheckedFor != isolate.id) {
            httpLoggingCheckedFor = isolate.id
            enableHttpLogging(connected, isolate.id)
        }
    }

    /** Called with no `enabled` argument, a bool extension only reports its value. */
    private fun readBool(connected: VmServiceClient, extension: String, isolateId: String): Boolean? = try {
        ExtensionResults.bool(connected.callServiceExtension(extension, isolateId))
    } catch (e: VmServiceException) {
        log.warn("Could not read $extension: ${e.message}")
        null
    }

    private fun enableHttpLogging(connected: VmServiceClient, isolateId: String) {
        if (readBool(connected, HTTP_LOGGING, isolateId) != false) return
        try {
            connected.callServiceExtension(HTTP_LOGGING, isolateId, mapOf("enabled" to "true"))
            httpLoggingEnabledBySpock += isolateId
        } catch (e: VmServiceException) {
            log.warn("Could not enable HTTP timeline logging: ${e.message}")
        }
    }

    /** Every isolate Spock switched on that is still alive; a paused one is left, as it would not answer. */
    private fun restoreHttpLogging(connected: VmServiceClient) {
        if (!connected.isOpen) return
        val selector = IsolateSelector(connected)
        httpLoggingEnabledBySpock.toList().forEach { isolateId ->
            httpLoggingEnabledBySpock -= isolateId
            try {
                val isolate = selector.describe(isolateId)
                if (isolate != null && !isolate.paused) {
                    connected.callServiceExtension(HTTP_LOGGING, isolateId, mapOf("enabled" to "false"))
                }
            } catch (e: VmServiceException) {
                log.warn("Could not switch HTTP timeline logging back off: ${e.message}")
            }
        }
    }

    private fun setState(newState: SessionState) {
        if (state == newState) return
        state = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    /** On the client's event thread. History never changes state: it is what was, not what is. */
    @Synchronized
    private fun onLiveEvent(event: VmServiceEvent) {
        if (client == null) return
        val ui = uiIsolate
        val ownIsolate = ui != null && event.isolateId == ui.id
        val kind = event.kind
        when {
            // With a UI isolate in hand, a start is a background isolate (`compute()` spawns them
            // all the time); a hot restart's new isolate is picked up when the old one exits.
            kind == "IsolateStart" -> if (ui == null) reselect()
            kind == "IsolateExit" -> onIsolateExit(event, ui == null || ownIsolate)
            kind == "ServiceExtensionAdded" -> onExtensionAdded(event, ui, ownIsolate)
            ui == null -> if (kind == "Resume") reselect()
            !ownIsolate -> Unit
            kind == "Resume" -> setState(SessionState.Connected(ui.id))
            kind in FlutterIsolate.PAUSED_KINDS -> setState(SessionState.Paused(ui.id, kind.orEmpty()))
            kind == "Extension" -> onExtensionEvent(event)
        }
    }

    private fun onIsolateExit(event: VmServiceEvent, affectsSelection: Boolean) {
        event.isolateId?.let { httpLoggingEnabledBySpock -= it }
        if (affectsSelection) reselect()
    }

    private fun onExtensionAdded(event: VmServiceEvent, ui: FlutterIsolate?, ownIsolate: Boolean) {
        val rpc = event.event.string("extensionRPC")
        when {
            ui == null -> reselect()
            ownIsolate && rpc != null -> {
                val updated = ui.withExtension(rpc)
                uiIsolate = updated
                setUp(updated)
            }
        }
    }

    /** Keeps [structuredErrorsEnabled] in step when another tool (DevTools, the IDE) toggles it. */
    private fun onExtensionEvent(event: VmServiceEvent) {
        if (event.extensionKind != STATE_CHANGED) return
        val data = event.event.get("extensionData") as? JsonObject ?: return
        if (data.string("extension") != FlutterIsolate.STRUCTURED_ERRORS) return
        ExtensionResults.bool(data, "value")?.let { structuredErrorsEnabled = it }
    }

    /** Bound to one client, so a late close or event from a previous connection is ignored. */
    private inner class ConnectionListener(private val owner: VmServiceClient) : VmServiceListener {
        override fun onEvent(event: VmServiceEvent) {
            if (client !== owner) return
            val accepted = history?.accept(event) ?: return
            if (!accepted.history) {
                try {
                    onLiveEvent(event)
                } catch (e: VmServiceException) {
                    // A restart mid-selection, or the connection going: the next event re-selects.
                    log.warn("Could not follow ${event.kind}: ${e.message}")
                }
            }
            listeners.forEach { it.onEvent(accepted) }
        }

        override fun onClosed(reason: String) {
            synchronized(this@FlutterSession) {
                if (client !== owner) return
                client = null
                candidate?.release()
                candidate = null
                uiIsolate = null
                setState(SessionState.Disconnected(reason))
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
        private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"

        private val log = Logger.getInstance(FlutterSession::class.java)
    }
}
