package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.device.ConnectedDevice
import spock.adb.diagnostics.FlutterDiagnosticSource
import spock.adb.diagnostics.FlutterWords
import spock.adb.flutter.FlutterAttachOutcome
import spock.adb.flutter.FlutterFollowerService
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.vmservice.Redaction

/** Read-only status of the selected app's Flutter runtime. The VM Service URI is never exposed. */
class FlutterAppStatusTool(
    private val sourceOf: (ToolContext, ConnectedDevice, String) -> FlutterDiagnosticSource? =
        { context, device, app ->
            FlutterFollowerService.getInstance(context.requireProject()).diagnosticSource(device, app)
        },
) : AdbTool {

    override val name = "flutter_app_status"
    override val description =
        "Report whether the selected app has a connected Flutter session, how Spock found and verified it, " +
            "the connection kind, build mode, UI isolate, Dart/Flutter versions when exposed, HTTP recording, " +
            "and device-clock state. Use this to check Flutter runtime observability before a diagnosis. " +
            "It never returns a VM Service address or token."

    override val safety = ToolSafety.READ_ONLY

    override val inputSchema: JsonObject = Schema.obj {
        string("applicationId", "Flutter application ID. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val app = arguments.optionalString("applicationId")
            ?: context.projectApplicationId()
            ?: return ToolResult.error(
                "No application ID is known. Pass applicationId, or open the app's project and let Gradle sync finish.",
            )
        val source = sourceOf(context, device, app)
            ?: return ToolResult.text(
                "Flutter app status for $app on ${device.serialNumber}:\n" +
                    "attach: Spock did not identify this package as a Flutter app.\n" +
                    "connected: no\n" +
                    "verifiedBy: unknown\nconnectionKind: unknown\nbuild: unknown\n" +
                    "uiIsolate: unknown\nflutterVersion: unknown\ndartVersion: unknown\n" +
                    "httpRecording: unknown\nclock: unknown",
            )
        return ToolResult.text(render(source))
    }

    internal fun render(source: FlutterDiagnosticSource): String = Redaction.scrub(
        buildString {
            val live = source.live
            val connected = source.outcome as? FlutterAttachOutcome.Connected
            val session = connected?.session
            val snapshot = live?.snapshot
            append("Flutter app status for ").append(source.applicationId).append(":\n")
            append("attach: ").append(FlutterWords.attach(source)).append('\n')
            append("connected: ").append(if (live != null) "yes" else "no").append('\n')
            append("verifiedBy: ").append(live?.identity?.verifiedBy?.label ?: "unknown")
            live?.identity?.pid?.let { append(" (pid ").append(it).append(')') }
            append('\n')
            append("connectionKind: ").append(FlutterWords.connection(snapshot?.connectionKind)).append('\n')
            append("build: ").append((live?.buildMode ?: source.build).label).append('\n')
            append("uiIsolate: ").append(isolate(snapshot?.uiIsolate?.id, snapshot?.uiIsolate?.name)).append('\n')
            append("flutterVersion: ").append(flutterVersion(session) ?: "unknown").append('\n')
            append("dartVersion: ").append(dartVersion(session) ?: "unknown").append('\n')
            append("httpRecording: ").append(FlutterWords.recording(snapshot?.httpRecording)).append('\n')
            append("clock: ").append(clock(live))
        },
    )

    private fun isolate(id: String?, name: String?): String = when {
        id == null -> "unknown"
        name.isNullOrBlank() -> id
        else -> "$id ($name)"
    }

    private fun dartVersion(session: FlutterSession?): String? =
        session?.vm?.get("version")?.takeIf { it.isJsonPrimitive }?.asString

    private fun flutterVersion(session: FlutterSession?): String? {
        val vm = session?.vm ?: return null
        return listOf("flutterVersion", "_flutterVersion", "flutterVersionString")
            .firstNotNullOfOrNull { key -> vm.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
    }

    private fun clock(live: FlutterDiagnosticSource.Live?): String {
        val time = live?.deviceTime ?: return "unknown: the device clock has not been measured"
        return buildString {
            append("zone ").append(time.zone.id)
            append(", uncertainty ±").append(time.uncertaintyMs).append(" ms")
            time.note?.let { append(" (").append(it).append(')') }
        }
    }
}
