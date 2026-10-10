package spock.adb.flutter

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.VmServiceEvent
import spock.adb.flutter.vmservice.string
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Where a [FlutterSession] stands. */
sealed interface SessionState {

    /** Connected. [isolateId] is null while no UI isolate is chosen; see [FlutterSession.selection]. */
    data class Connected(val isolateId: String?) : SessionState

    /** The UI isolate is paused in the debugger: extension calls would hang, so none are made [FR12]. */
    data class Paused(val isolateId: String, val pauseKind: String) : SessionState {
        val message: String get() = "The app is paused in the debugger ($pauseKind)."
    }

    data class Disconnected(val reason: String) : SessionState
}

/**
 * What a [FlutterSession] knows, as one value: read [FlutterSession.snapshot] once and the state,
 * the UI isolate and the structured-errors flag agree with each other, which separate reads of
 * the session's properties need not. A `Connected` state with an isolate is published only once
 * that isolate is set up, so [structuredErrorsEnabled] is already read then.
 *
 * The connection's own facts — [connectionKind], [connectedAtHostMs] and the VM's identity — are
 * null while not connected.
 */
data class FlutterSessionSnapshot(
    val state: SessionState,
    val selection: IsolateSelection? = null,
    val uiIsolate: FlutterIsolate? = null,
    /** Null until read, or when the UI isolate has no inspector (profile builds). */
    val structuredErrorsEnabled: Boolean? = null,
    /** What answered on connect. Only [ConnectionKind.DDS] is written to. */
    val connectionKind: ConnectionKind? = null,
    /** When the connection opened, on the host's clock (the session's clock). */
    val connectedAtHostMs: Long? = null,
    /** `getVM().pid`: the app's process on the device. */
    val vmPid: Int? = null,
    /** `getVM().startTime`: when the VM started, epoch ms on the **device's** clock. */
    val vmStartTimeMs: Long? = null,
    /** `getVM().operatingSystem`: `android`, `ios`, `macos`… */
    val operatingSystem: String? = null,
    /** HTTP timeline logging on the UI isolate; null until decided for it. */
    val httpRecording: HttpRecording? = null,
    /**
     * What this connection did about rebuild tracking an earlier session's recording left on
     * ([LeftOnRebuildFlag]); null when nothing was owed, or not settled yet.
     */
    val rebuildFlagRestore: RebuildFlagRestore? = null,
) {
    val uiIsolateId: String? get() = uiIsolate?.id

    /** This, with the facts of the connection that ended taken out. */
    internal fun withoutConnection(): FlutterSessionSnapshot = copy(
        uiIsolate = null,
        connectionKind = null,
        connectedAtHostMs = null,
        vmPid = null,
        vmStartTimeMs = null,
        operatingSystem = null,
        httpRecording = null,
        rebuildFlagRestore = null,
    )
}

/**
 * A VM Service event as the session hands it on.
 *
 * [history] marks an event DDS replayed on subscribe — it happened before Spock connected — so a
 * caller can show it without treating it as something the app just did. See [EventHistory].
 */
class FlutterEvent(val event: VmServiceEvent, val history: Boolean) {
    val streamId: String get() = event.streamId
    val kind: String? get() = event.kind
    val extensionKind: String? get() = event.extensionKind
    val isolateId: String? get() = event.isolateId
    val timestamp: Long? get() = event.timestamp

    override fun toString(): String = "FlutterEvent($event${if (history) ", history" else ""})"
}

/**
 * Called on the VM Service client's event thread — never the EDT, so marshal UI work with
 * `invokeLater`. A plain interface rather than a coroutine `Flow`, which would tie this code to
 * the coroutines version bundled with the IDE.
 */
interface FlutterSessionListener {
    fun onEvent(event: FlutterEvent)

    /**
     * In the order the state changed, one call at a time, and never while the session holds its
     * lock — so a listener may call back into the session. On whichever session thread is
     * delivering: the caller's during [FlutterSession.connect], else the event or session thread.
     * [state] may already be stale when it arrives; act on [FlutterSession.snapshot]. A listener
     * added with `replayState` first hears the state at the time it was added.
     */
    fun onStateChanged(state: SessionState) = Unit
}

/**
 * Tells replayed events from live ones and drops repeated replays. One per session, kept across
 * reconnects, so a reconnect's replay of what the last connection already delivered is dropped.
 *
 * DDS replays up to 10 000 past `Extension`, `Logging`, `Stdout` and `Stderr` events to each new
 * subscriber — only those four, so an `Isolate`, `Debug` or `Service` event is never history and
 * always drives the session. The replay is sent from DDS's `streamListen` handler, before its
 * answer, so an event that arrived while Spock's `streamListen` for its stream was still waiting
 * ([VmServiceEvent.duringListen]) is history. Timestamps are on the device's clock, which may be
 * hours off the host's, so they are only a hint, and only against each other: an event older than
 * the newest replayed one on its stream, in this connection, is history too.
 *
 * A repeat is a replayed event whose timestamp, isolate, kind, extension kind and content all
 * match one seen recently; the content key is a log record's sequence number and text, a write's
 * first bytes, or an extension event's top-level values — exact strings, not a hash, so two
 * distinct events never collide by chance. A live event is never dropped.
 */
internal class EventHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private data class Key(
        val streamId: String,
        val timestamp: Long,
        val isolateId: String?,
        val kind: String?,
        val extensionKind: String?,
        val content: String,
    )

    private val seen = object : LinkedHashMap<Key, Unit>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Unit>?): Boolean = size > capacity
    }

    /** Stream → the newest timestamp replayed on it in this connection. */
    private val newestReplayed = HashMap<String, Long>()

    /** A new connection replays again; what was seen stays seen. */
    @Synchronized
    fun newConnection() = newestReplayed.clear()

    /** Null for a repeated replay; otherwise the event, marked as history when it was replayed. */
    @Synchronized
    fun accept(event: VmServiceEvent): FlutterEvent? {
        if (event.streamId !in REPLAYED_STREAMS) return FlutterEvent(event, history = false)
        val timestamp = event.timestamp ?: return FlutterEvent(event, history = event.duringListen)
        val history = event.duringListen || timestamp < (newestReplayed[event.streamId] ?: Long.MIN_VALUE)
        val content = contentOf(event.event)
        val key = Key(event.streamId, timestamp, event.isolateId, event.kind, event.extensionKind, content)
        val repeat = seen.put(key, Unit) != null
        if (!history) return FlutterEvent(event, history = false)
        if (repeat) return null
        newestReplayed.merge(event.streamId, timestamp, ::maxOf)
        return FlutterEvent(event, history = true)
    }

    private fun contentOf(event: JsonObject): String {
        val record = event.get("logRecord") as? JsonObject
        val data = event.get("extensionData") as? JsonObject
        return when {
            record != null -> {
                val message = (record.get("message") as? JsonObject)?.string("valueAsString")
                    ?: record.string("message")
                "${record.get("sequenceNumber")}:${message.orEmpty().take(CONTENT_CHARS)}"
            }
            event.has("bytes") -> event.string("bytes").orEmpty().let { "${it.length}:${it.take(CONTENT_CHARS)}" }
            data != null -> data.entrySet().filter { it.value is JsonPrimitive }
                .joinToString(",") { "${it.key}=${it.value}" }.take(DATA_CHARS)
            else -> ""
        }
    }

    companion object {
        /** As many as DDS replays, so a whole replay can be recognised. */
        const val DEFAULT_CAPACITY = 10_000
        private const val LOAD_FACTOR = 0.75f
        private const val CONTENT_CHARS = 64
        private const val DATA_CHARS = 256

        /** The streams DDS keeps and replays. */
        val REPLAYED_STREAMS = setOf("Extension", "Logging", "Stdout", "Stderr")
    }
}

/**
 * Hands state changes to [listeners] in order, one at a time, outside the session's lock.
 * Changes are queued under the lock and delivered by whichever thread calls [flush] next; a
 * thread that finds another delivering leaves the queue to it, so a listener that calls back
 * into the session neither deadlocks nor reorders. A change goes to those listening when it was
 * queued and still listening when it is delivered, so a listener added late, with [enqueueFor]
 * its catch-up, hears no change twice, and one removed hears nothing more.
 */
internal class StateNotifier(private val listeners: CopyOnWriteArrayList<FlutterSessionListener>) {

    private class Delivery(val state: SessionState, val to: List<FlutterSessionListener>)

    private val queue = ConcurrentLinkedQueue<Delivery>()
    private val delivering = AtomicBoolean()

    fun enqueue(state: SessionState) {
        queue += Delivery(state, listeners.toList())
    }

    /** [state] for [listener] alone: the state as it was when the listener was added. */
    fun enqueueFor(listener: FlutterSessionListener, state: SessionState) {
        queue += Delivery(state, listOf(listener))
    }

    fun flush() {
        while (queue.isNotEmpty() && delivering.compareAndSet(false, true)) {
            try {
                while (true) deliver(queue.poll() ?: break)
            } finally {
                delivering.set(false)
            }
        }
    }

    // A listener's bug must cost neither the other listeners nor the session's own work.
    @Suppress("TooGenericExceptionCaught")
    private fun deliver(delivery: Delivery) = delivery.to.forEach { listener ->
        // Removed since the change was queued: it hears nothing more.
        if (listener !in listeners) return@forEach
        val state = delivery.state
        try {
            listener.onStateChanged(state)
        } catch (e: Exception) {
            log.warn("Flutter session listener failed on $state", e)
        }
    }

    private companion object {
        val log = Logger.getInstance(StateNotifier::class.java)
    }
}
