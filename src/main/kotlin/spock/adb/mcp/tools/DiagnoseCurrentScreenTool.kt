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
    override val title = "Diagnose current screen"

    override val description =
        "Diagnose the screen shown now: android_get_debug_context's report with every section, plus a " +
            "screenshot of the same moment."

    override val safety = ToolSafety.READ_ONLY

    override val outputSchema: JsonObject = DIAGNOSTIC_REPORT_SCHEMA

    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "The app the screen belongs to. $PACKAGE_DEFAULT_OR_DEVICE")
        boolean(
            "screenshot",
            "Attach a screenshot. Default true; false when text is enough, as the image costs most.",
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
