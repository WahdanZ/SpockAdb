package spock.adb.flutter.analysis

import com.google.gson.JsonObject
import spock.adb.flutter.FlutterEvent

/**
 * One VM Service `Extension` event, in the shape the analyzers read.
 *
 * The analyzers depend on this and nothing else from the session, so a change inside
 * `FlutterSession` touches [from] only.
 *
 * @param kind the `extensionKind`: `Flutter.Error`, `Flutter.Frame`, …
 * @param timestampMs the event's `timestamp`: epoch milliseconds on the **device's** clock, when
 *   the app posted it. Moving it onto the host's clock is the caller's job.
 * @param data the `extensionData`, already scrubbed of VM Service tokens by the session.
 * @param history replayed by DDS on subscribe: it happened before Spock connected.
 */
data class FlutterExtensionEvent(
    val kind: String,
    val timestampMs: Long,
    val isolateId: String?,
    val data: JsonObject,
    val history: Boolean = false,
) {
    companion object {
        const val ERROR = "Flutter.Error"
        const val FRAME = "Flutter.Frame"
        const val REBUILT_WIDGETS = "Flutter.RebuiltWidgets"
        const val NAVIGATION = "Flutter.Navigation"

        /** Null for anything that is not an `Extension` event with a timestamp and a data object. */
        fun from(event: FlutterEvent): FlutterExtensionEvent? {
            val kind = event.extensionKind ?: return null
            val timestamp = event.timestamp ?: return null
            val data = event.event.event.get("extensionData") as? JsonObject ?: return null
            return FlutterExtensionEvent(kind, timestamp, event.isolateId, data, event.history)
        }
    }
}
