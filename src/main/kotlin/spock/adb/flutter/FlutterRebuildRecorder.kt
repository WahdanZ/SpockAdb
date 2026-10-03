package spock.adb.flutter

import com.google.gson.JsonObject
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.RebuildTracker
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.ExtensionResults

/**
 * Records Flutter widget rebuilds for one explicit window.
 *
 * This changes the app only while the caller is recording: it enables Flutter's inspector rebuild
 * tracking only when it was off, listens to Flutter.RebuiltWidgets, and restores the shared flag
 * afterwards only when Spock owned the change. Diagnose never calls this.
 */
class FlutterRebuildRecorder(
    private val wait: (Long) -> Unit = { Thread.sleep(it) },
) {

    fun record(session: FlutterSession, durationMs: Long, limit: Int = RebuildTracker.DEFAULT_LIMIT): RebuildTracker.Report {
        require(session.connectionKind == ConnectionKind.DDS) {
            "Flutter rebuild recording needs a DDS session; a direct VM connection is read-only."
        }
        require(session.buildMode == FlutterBuild.DEBUG) {
            "Flutter rebuild recording is available only in a debug build."
        }
        val isolate = session.uiIsolate ?: error("No running Flutter UI isolate is selected.")
        require(TRACK_REBUILDS in isolate.extensionRpcs) {
            "This Flutter isolate does not expose rebuild tracking."
        }
        require(LOCATION_MAP in isolate.extensionRpcs) {
            "This Flutter isolate does not expose widget source locations."
        }

        val tracker = RebuildTracker()
        val listener = object : FlutterSessionListener {
            override fun onEvent(event: FlutterEvent) {
                FlutterExtensionEvent.from(event)?.let(tracker::accept)
            }
        }
        session.addListener(listener)

        var owned = false
        try {
            val locations = session.callUiExtension(LOCATION_MAP)
            tracker.seedLocations(locationMap(locations), isolate.id)

            val before = ExtensionResults.bool(session.callUiExtension(TRACK_REBUILDS))
                ?: error("Flutter did not report whether rebuild tracking is enabled.")
            if (!before) {
                val enabled = ExtensionResults.bool(
                    session.callUiExtension(TRACK_REBUILDS, mapOf("enabled" to "true")),
                )
                require(enabled == true) { "Flutter refused to enable rebuild tracking." }
                owned = true
            }

            wait(durationMs.coerceAtLeast(0))
            return tracker.report(windowMs = durationMs.coerceAtLeast(0), limit = limit)
        } finally {
            session.removeListener(listener)
            if (owned && session.connectionKind == ConnectionKind.DDS) {
                runCatching {
                    session.callUiExtension(TRACK_REBUILDS, mapOf("enabled" to "false"))
                }
            }
        }
    }

    /** Flutter versions have returned the location map either directly or under a wrapper key. */
    private fun locationMap(result: JsonObject): JsonObject =
        (result.get("result") as? JsonObject)
            ?: (result.get("locations") as? JsonObject)
            ?: result

    companion object {
        const val TRACK_REBUILDS = "ext.flutter.inspector.trackRebuildDirtyWidgets"
        const val LOCATION_MAP = "ext.flutter.inspector.widgetLocationIdMap"
    }
}
