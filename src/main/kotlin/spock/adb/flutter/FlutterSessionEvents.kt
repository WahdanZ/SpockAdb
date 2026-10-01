package spock.adb.flutter

import spock.adb.flutter.vmservice.VmServiceEvent

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
 * A VM Service event as the session hands it on.
 *
 * [history] marks an event DDS replayed on subscribe — it happened before Spock connected — so a
 * caller can show it without treating it as something the app just did.
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

    /** On whichever thread changed it: the caller's during [FlutterSession.connect], else the event thread. */
    fun onStateChanged(state: SessionState) = Unit
}

/**
 * Tells replayed events from live ones and drops repeats.
 *
 * DDS replays up to 10 000 past `Extension`, `Logging`, `Stdout` and `Stderr` events to each new
 * subscriber, so a re-subscribe would deliver the same events twice. An event is a repeat when
 * its timestamp, isolate, kind, extension kind and content all match one seen recently. The
 * content is part of the key because two log records can share a millisecond.
 */
internal class EventHistory(private val connectedAt: Long, private val capacity: Int = DEFAULT_CAPACITY) {

    private data class Key(
        val timestamp: Long,
        val isolateId: String?,
        val kind: String?,
        val extensionKind: String?,
        val content: Int,
    )

    private val seen = object : LinkedHashMap<Key, Unit>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Unit>?): Boolean = size > capacity
    }

    /** Null for a repeat; otherwise the event, marked as history when it predates the connection. */
    @Synchronized
    fun accept(event: VmServiceEvent): FlutterEvent? {
        val timestamp = event.timestamp ?: return FlutterEvent(event, history = false)
        val key = Key(timestamp, event.isolateId, event.kind, event.extensionKind, event.event.toString().hashCode())
        if (seen.put(key, Unit) != null) return null
        return FlutterEvent(event, history = timestamp < connectedAt)
    }

    companion object {
        /** As many as DDS replays, so a whole replay can be recognised. */
        const val DEFAULT_CAPACITY = 10_000
        private const val LOAD_FACTOR = 0.75f
    }
}
