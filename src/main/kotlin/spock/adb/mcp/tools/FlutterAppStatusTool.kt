package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.device.ConnectedDevice
import spock.adb.diagnostics.DiagnosticCollector
import spock.adb.diagnostics.FlutterDiagnosticSource
import spock.adb.diagnostics.FlutterWords
import spock.adb.flutter.FlutterAppStatus
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterBuildCache
import spock.adb.flutter.FlutterFollowerService
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.vmservice.Redaction

/**
 * `flutter_app_status` — whether Spock has a Flutter session on the app, and what it knows: the
 * one Flutter tool the plan allows [FR15]. Everything else about a Flutter app rides the existing
 * tools (Diagnose, the Timeline, the rebuild window in `android_get_recomposition_counts`).
 *
 * Read-only, like Diagnose: it attaches as Diagnose does when there is no session yet, and reads
 * the session's state; it calls nothing in the app. Never prints a VM Service address or token:
 * the session hands none out, and the whole answer is scrubbed once more before it leaves.
 *
 * @param statusOf what the status is, for [execute]; the project's [FlutterFollowerService] by
 *   default. Tests pass their own.
 */
class FlutterAppStatusTool(
    private val statusOf: (ToolContext, ConnectedDevice, String) -> FlutterAppStatus =
        { context, device, packageName -> projectStatus(context, device, packageName) },
) : AdbTool {
    override val name = "flutter_app_status"
    override val description =
        "Report Spock's connection to a running Flutter app's Dart VM Service: whether it is a Flutter " +
            "app and its build (from the APK), how the attach went in words (connected, not running, " +
            "release build, starting, no debugger session, ambiguous, not found, failed), how the session " +
            "was found and verified (Dart Tooling Daemon or logcat, by pid and process start), the " +
            "connection kind (DDS, or a read-only direct VM), the build mode, the UI isolate, the Dart " +
            "version (the Flutter version when the app reports it, else unknown), whether Spock records " +
            "HTTP, and the device clock Spock pairs Flutter events with log lines by. Attaches by itself " +
            "within about 3 seconds when there is no session yet, as android_diagnose_current_screen does. " +
            "Call it when Diagnose's flutter section says the session is missing, to see why. It never " +
            "returns a VM Service address or token. Widgets, hot reload and Flutter's own errors belong " +
            "to the Dart MCP server; run it next to Spock."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "The Flutter app's application ID. Defaults to the open project's.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        return ToolResult.text(render(statusOf(context, device, packageName)))
    }

    /** The status as JSON, scrubbed of any VM Service address as a last guard [FR9]. */
    internal fun render(status: FlutterAppStatus): String {
        val json = JsonObject().apply {
            addProperty("applicationId", status.applicationId)
            addProperty("device", status.serial)
            addProperty("flutterApp", status.build != null)
            val build = status.build
            if (build == null) {
                addProperty("attach", "${status.applicationId} is not a Flutter app: its APK has no Flutter engine.")
                return@apply
            }
            addProperty("apkBuild", build.label)
            val words = FlutterDiagnosticSource(status.applicationId, build, status.outcome, status.note)
            addProperty("attach", FlutterWords.attach(words))
            addProperty("connected", status.live != null)
            status.live?.let { live(it) }
        }
        // Redaction blanks the token and keeps the host and port; this answer keeps neither.
        return Redaction.scrub(DiagnosticCollector.render(json)).replace(LOOPBACK_URL, ADDRESS)
    }

    private fun JsonObject.live(live: FlutterAppStatus.Live) {
        val snapshot = live.snapshot
        add(
            "identity",
            JsonObject().apply {
                addProperty("pid", live.identity.pid ?: snapshot.vmPid?.toLong())
                addProperty("verifiedBy", live.identity.verifiedBy.label)
                addProperty("how", verification(live.identity.verifiedBy))
            },
        )
        addProperty("connectionKind", FlutterWords.connection(snapshot.connectionKind))
        addProperty("buildMode", live.buildMode?.label ?: BUILD_MODE_UNKNOWN)
        add(
            "uiIsolate",
            JsonObject().apply {
                val isolate = snapshot.uiIsolate
                if (isolate == null) {
                    addProperty("note", "No UI isolate is selected yet.")
                } else {
                    addProperty("id", isolate.id)
                    addProperty("name", isolate.name)
                    if (isolate.paused) addProperty("paused", isolate.pauseKind)
                }
            },
        )
        add(
            "versions",
            JsonObject().apply {
                addProperty("flutter", FLUTTER_VERSION_UNKNOWN)
                addProperty("dart", live.dartVersion ?: "unknown: the VM did not say")
            },
        )
        addProperty("structuredErrors", FlutterWords.onOff(snapshot.structuredErrorsEnabled))
        addProperty("httpRecording", FlutterWords.recording(snapshot.httpRecording))
        add("clock", clock(live))
    }

    private fun clock(live: FlutterAppStatus.Live) = JsonObject().apply {
        val time = live.deviceTime
        when {
            time != null -> {
                addProperty("state", "measured")
                addProperty("zone", time.zone.id)
                addProperty("uncertaintyMs", time.uncertaintyMs)
                time.note?.let { addProperty("note", it) }
            }
            !live.clockDone -> {
                addProperty("state", "measuring")
                addProperty("note", "The session is new: Spock is reading the device's clock now.")
            }
            else -> {
                addProperty("state", "unavailable")
                addProperty(
                    "note",
                    "The device's clock could not be read: device times are shown in UTC, and Flutter " +
                        "errors are not paired with log lines.",
                )
            }
        }
    }

    private fun verification(check: IdentityCheck): String = when (check) {
        IdentityCheck.DTD_PID_START ->
            "Listed by a Dart Tooling Daemon; its VM runs as the app's pid on this device and started with it."
        IdentityCheck.DTD_PID_ONLY ->
            "Listed by a Dart Tooling Daemon; its VM runs as the app's pid on this device. The process start " +
                "could not be read, so only the pid was compared."
        IdentityCheck.DTD_PID_AMBIGUOUS -> "Several Dart Tooling Daemon apps pass as this app: offered, not chosen."
        IdentityCheck.LOGCAT_PID -> "Announced in logcat by the app's own pid on this device, its VM on that pid."
        IdentityCheck.DTD_NAME_UNVERIFIED ->
            "The only app a Dart Tooling Daemon lists for this project and device model; not checked by pid."
        IdentityCheck.PASTED_UNVERIFIED -> "Pasted by the developer; not checked."
    }

    private companion object {
        /** A VM Service, DDS or DevTools address: loopback, as every one Spock connects to is. */
        val LOOPBACK_URL = Regex("""\b(?:wss?|https?)://(?:127\.0\.0\.1|localhost|\[::1]|0\.0\.0\.0)(?::\d+)?[^\s"]*""")
        const val ADDRESS = "<VM Service address>"
        const val BUILD_MODE_UNKNOWN = "unknown: the UI isolate has not registered its extensions yet"

        /**
         * No service extension of the framework reports its version; `flutter_tools` registers one
         * through DDS, under a name only its `Service` stream announces, which Spock does not follow.
         */
        const val FLUTTER_VERSION_UNKNOWN =
            "unknown: the app does not report it; `flutter --version` on the machine that ran it does"

        /** Through the open project's session; not Flutter → no attach at all. */
        fun projectStatus(context: ToolContext, device: ConnectedDevice, packageName: String): FlutterAppStatus {
            val build: FlutterBuild = FlutterBuildCache.shared.detectOn(device.device, device.serialNumber, packageName)
                ?: return FlutterAppStatus(packageName, device.serialNumber, null, null)
            val project = context.project ?: return FlutterAppStatus(
                packageName,
                device.serialNumber,
                build,
                outcome = null,
                note = "No project is open: Spock's Flutter session belongs to the project that runs the app.",
            )
            return FlutterFollowerService.getInstance(project).status(device, packageName, build)
        }
    }
}
