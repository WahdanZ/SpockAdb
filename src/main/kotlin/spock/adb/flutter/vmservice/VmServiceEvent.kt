package spock.adb.flutter.vmservice

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * One `streamNotify` from the VM Service, already passed through [Redaction.scrubEvent].
 *
 * [event] is the protocol's `Event` object; the accessors read the fields most callers need.
 * [duringListen] says it arrived while this client's `streamListen` for [streamId] was still
 * waiting for its answer — which is when DDS replays a stream's history to a new subscriber.
 */
class VmServiceEvent(val streamId: String, val event: JsonObject, val duringListen: Boolean = false) {

    /** `IsolateStart`, `Extension`, `PauseBreakpoint`, `Logging`, … */
    val kind: String? get() = event.string("kind")

    /** `Flutter.Frame`, `Flutter.Error`, … for `Extension` events. */
    val extensionKind: String? get() = event.string("extensionKind")

    val isolateId: String? get() = (event.get("isolate") as? JsonObject)?.string("id")

    /** Milliseconds since the epoch on the VM's clock — the device's, not the host's. */
    val timestamp: Long? get() = (event.get("timestamp") as? JsonPrimitive)?.takeIf { it.isNumber }?.asLong

    override fun toString(): String = "VmServiceEvent($streamId, $kind${extensionKind?.let { " $it" } ?: ""})"
}

/** Events and the end of the connection, delivered on the client's event thread, in order. */
interface VmServiceListener {
    fun onEvent(event: VmServiceEvent)

    /** Once, after the last event: [reason] says whether Spock closed it or it was lost. */
    fun onClosed(reason: String) = Unit
}

internal fun JsonObject.string(key: String): String? {
    val value: JsonElement = get(key) ?: return null
    return if (value is JsonPrimitive && value.isString) value.asString else null
}
