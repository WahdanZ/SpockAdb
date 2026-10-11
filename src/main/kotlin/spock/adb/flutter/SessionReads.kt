package spock.adb.flutter

import com.google.gson.JsonObject
import spock.adb.flutter.analysis.FrameStats

/** The reads Diagnose and the Timeline make of a live session. Blocking: off the EDT. */
object SessionReads {

    const val HTTP_PROFILE = "ext.dart.io.getHttpProfile"

    /**
     * `_flutter.getDisplayRefreshRate` of the first view, the frame budget's source; null when the
     * app does not serve it.
     *
     * @throws spock.adb.flutter.vmservice.VmServiceException when the call fails.
     */
    fun refreshRate(session: FlutterSession, timeoutMs: Long): Double? {
        val views = session.callVm("_flutter.listViews", JsonObject(), timeoutMs).getAsJsonArray("views")
        val viewId = (views?.firstOrNull() as? JsonObject)?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return null
        val params = JsonObject().apply { addProperty("viewId", viewId) }
        return FrameStats.refreshRate(session.callVm("_flutter.getDisplayRefreshRate", params, timeoutMs))
    }

    /**
     * The UI isolate's HTTP profile, scrubbed by the session; only what changed since
     * [updatedSinceUs] (the last profile's `timestamp`) when given. A read Spock may make on any
     * connection.
     *
     * @throws spock.adb.flutter.vmservice.VmServiceException when the call fails.
     */
    fun httpProfile(session: FlutterSession, updatedSinceUs: Long?, timeoutMs: Long): JsonObject =
        session.callUiExtension(
            HTTP_PROFILE,
            updatedSinceUs?.let { mapOf("updatedSince" to it.toString()) }.orEmpty(),
            timeoutMs,
        )
}
