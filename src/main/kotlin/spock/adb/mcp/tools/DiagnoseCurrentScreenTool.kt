package spock.adb.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import spock.adb.diagnostics.DiagnosticSections

/**
 * `android_diagnose_current_screen` — everything known about the screen in front of the user, in
 * one call: the same report as the IDE's Diagnose tab.
 *
 * Deliberately a preset of [DebugContextTool] rather than a second implementation. The two answer
 * the same question at different prices: `android_get_debug_context` is the cheap first look an
 * assistant attaches to every conversation, so it leaves the screenshot out; this is the
 * "show me everything" a developer asks for when a screen is wrong, so it captures every section
 * and a screenshot of the same moment. One collector behind both means one schema, one set of
 * size caps and one redaction pass — two would drift, and the one that drifted would leak.
 */
class DiagnoseCurrentScreenTool : AdbTool {

    override val name = "android_diagnose_current_screen"

    override val description =
        "Diagnose the screen currently shown on the device for the selected app, in one call. " +
            "Returns the same bounded JSON report as android_get_debug_context with every " +
            "section — likelyProblems ranked first, then the current activity, the app's " +
            "activity stack and fragments, process state, log problem counts, a UI and " +
            "accessibility summary, runtime permissions, scheduled jobs and alarms, and device " +
            "conditions (Doze, standby bucket, battery, charger) — plus a screenshot of the same " +
            "moment. A failing section is reported under sectionErrors and never fails the call. " +
            "Each section names the tool that returns its raw data under 'more'."

    override val safety = ToolSafety.READ_ONLY

    override val inputSchema: JsonObject = Schema.obj {
        string(
            "packageName",
            "The app the screen belongs to. Defaults to the open project's application ID. " +
                "Pass an empty string to consider the whole device.",
        )
        boolean(
            "screenshot",
            "Attach a screenshot of the screen. Defaults to true; pass false when you only need " +
                "the text, since the image is by far the most expensive part.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val include = JsonArray().apply {
            DiagnosticSections.ALL.forEach { add(it.id) }
            if (arguments.optionalBoolean("screenshot", true)) add(SCREENSHOT)
        }
        val delegated = JsonObject().apply {
            addProperty("format", "summary")
            add("include", include)
            arguments.get("packageName")?.let { add("packageName", it) }
            arguments.get("deviceSerial")?.let { add("deviceSerial", it) }
        }
        return DebugContextTool().execute(delegated, context)
    }

    private companion object {
        const val SCREENSHOT = "screenshot"
    }
}
