package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.diagnostics.DiagnosticCollector
import spock.adb.diagnostics.DiagnosticProbe
import spock.adb.diagnostics.DiagnosticSection
import spock.adb.diagnostics.DiagnosticSections
import spock.adb.diagnostics.DiagnosticShell

/**
 * `android_get_debug_context` — the whole triage bundle in one call.
 *
 * Two shapes. The default, `format: "summary"`, is a bounded JSON report built by
 * [DiagnosticCollector]: ranked likely problems first, then one short summary per section, then
 * the tool call that returns each section's raw data. It embeds no raw logcat and no UI tree —
 * an agent reads the answer, and fetches the detail only when the answer points there.
 * `format: "full"` is the 4.x text bundle, unchanged, for clients that parse it.
 *
 * Answering "why does this screen look wrong" previously cost an agent three or four
 * round-trips: current activity, UI tree, logcat, screenshot. Each is a separate turn, and by
 * the time the last one lands the screen may have moved on — so the bundle it assembled
 * describes no single moment. Capturing them together is both cheaper and more truthful.
 *
 * A failing section does not fail the call. A screenshot blocked by `FLAG_SECURE` must not
 * cost the developer the crash sitting next to it in logcat, so each section reports its own
 * failure in place and the rest still come back.
 */
class DebugContextTool : AdbTool {

    override val name = "android_get_debug_context"

    override val description =
        "Start here when asked why a screen looks wrong, why an app crashed, or what state the " +
            "app is in. Returns a bounded JSON summary of the same moment: likelyProblems ranked " +
            "(crashes, ANRs, failed HTTP requests, exceptions, repeated errors), then the current " +
            "screen, whether the app is running, log counts, a UI and accessibility summary, " +
            "background work and device conditions. Raw logcat and the UI tree are not included; " +
            "each section names the tool that returns its detail under 'more'. Pass format=full " +
            "for the older text bundle with raw logcat and the UI tree."

    override val safety = ToolSafety.READ_ONLY

    override val inputSchema: JsonObject = Schema.obj {
        enumeration(
            "format",
            "summary (default): bounded JSON, problems first, no raw data. full: the older text " +
                "bundle with raw logcat and the UI tree, for when you need the raw data in one call.",
            listOf(FORMAT_SUMMARY, FORMAT_FULL),
        )
        stringArray(
            "include",
            "Which sections to capture. Summary sections: " +
                DiagnosticSections.ALL.joinToString { it.id } + ", all by default. Full-format " +
                "sections: activity, ui and logcat by default. Either format accepts \"screenshot\", " +
                "attached as an image; add it only when you need to see the screen rather than read " +
                "about it — it is by far the most expensive section.",
            values = (DiagnosticSections.ALL.map { it.id } + Section.ALL.map { it.id }).distinct(),
        )
        string(
            "packageName",
            "The app the question is about. Defaults to the open project's application ID. " +
                "Pass an empty string to consider the whole device.",
        )
        enumeration(
            "minLevel",
            "full format only: minimum logcat level. Defaults to V; use E when hunting a crash.",
            listOf("V", "D", "I", "W", "E", "F"),
        )
        integer(
            "maxLogcatLines",
            "summary: log lines scanned for problems, default 1500. full: lines included, default " +
                "200. Capped at 2000.",
        )
        integer("maxUiDepth", "full format only: how deep to render the UI tree. Defaults to 25.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        when (val format = arguments.optionalString("format")?.lowercase() ?: FORMAT_SUMMARY) {
            FORMAT_SUMMARY -> summary(arguments, context)
            FORMAT_FULL -> full(arguments, context)
            else -> ToolResult.error("Unknown format '$format'. Use $FORMAT_SUMMARY or $FORMAT_FULL.")
        }

    /** Schema version 2: see [DiagnosticCollector] and docs/MCP.md. */
    private fun summary(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val requested = arguments.optionalStringList("include")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val sections: List<DiagnosticSection> = when {
            requested.isNullOrEmpty() -> DiagnosticSections.ALL
            else -> DiagnosticSections.ALL.filter { section ->
                requested.any { DiagnosticSections.byId(it) == section }
            }
        }
        val wantsScreenshot = requested.orEmpty().any { it.equals(Section.SCREENSHOT.id, ignoreCase = true) }
        if (sections.isEmpty() && !wantsScreenshot) {
            return ToolResult.error(
                "No known section was requested. 'include' accepts any of: " +
                    (DiagnosticSections.ALL.map { it.id } + Section.SCREENSHOT.id).joinToString() + ".",
            )
        }

        val probe = DiagnosticProbe(
            device = device.device,
            serialNumber = device.serialNumber,
            packageName = with(LogcatReader) { arguments.logcatPackage(context) },
            logWindowLines = arguments.optionalInt("maxLogcatLines", DiagnosticProbe.DEFAULT_LOG_WINDOW_LINES)
                .coerceIn(1, MAX_LOGCAT_LINES),
        )
        val preamble = JsonObject().apply {
            add(
                "device",
                JsonObject().apply {
                    addProperty("serial", device.serialNumber)
                    addProperty("description", device.info.describe())
                },
            )
        }
        val report = DiagnosticCollector().collect(sections, probe, preamble)

        val content = mutableListOf<ToolContent>()
        if (wantsScreenshot) {
            val shot = runCatching { TakeScreenshotTool().execute(arguments, context) }.getOrNull()
            val image = shot?.content?.filterIsInstance<ToolContent.Image>()?.firstOrNull()
            report.addProperty(
                "screenshot",
                // Added after the collector's size cut, so clipped here: a failed capture's message
                // can carry tens of kilobytes of raw device output.
                if (image != null) {
                    "attached"
                } else {
                    shot?.let { DiagnosticShell.clip(textOf(it), MAX_SCREENSHOT_NOTE_CHARS) } ?: "could not be captured"
                },
            )
            image?.let { content += it }
        }
        content.add(0, ToolContent.Text(DiagnosticCollector.render(report)))
        return ToolResult(content)
    }

    /** The 4.x bundle, byte for byte, for clients that parse its headings. */
    private fun full(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val sections = resolveSections(arguments)
        if (sections.isEmpty()) {
            return ToolResult.error(
                "No known section was requested. 'include' accepts any of: " +
                    Section.ALL.joinToString { it.id } + ".",
            )
        }

        val deadline = System.nanoTime() + BUDGET_SECONDS * NANOS_PER_SECOND
        val report = StringBuilder("Debug context for ").appendLine(device.info.describe())
        var screenshot: ToolContent.Image? = null

        sections.forEach { section ->
            report.append('\n').append("## ").appendLine(section.heading)

            if (System.nanoTime() > deadline) {
                report.appendLine(
                    "Skipped: the ${BUDGET_SECONDS}s budget for this call was already spent. " +
                        "Request fewer sections, or call the individual tool for this one.",
                )
                return@forEach
            }

            val captured = runCatching { capture(section, arguments, context, device) }
                .getOrElse { failure ->
                    // An agent can act on "the screen is off"; it cannot act on a stack trace.
                    Captured(
                        "Could not capture this section: " +
                            (failure.message ?: failure::class.java.simpleName),
                    )
                }
            captured.image?.let { screenshot = it }
            report.appendLine(captured.text)
        }

        val text = with(McpShell) { report.toString().trimEnd().truncateForAgent(MAX_CHARS) }
        val content = mutableListOf<ToolContent>(ToolContent.Text(text))
        screenshot?.let { content += it }
        return ToolResult(content)
    }

    private fun capture(
        section: Section,
        arguments: JsonObject,
        context: ToolContext,
        device: spock.adb.device.ConnectedDevice,
    ): Captured = when (section) {
        Section.ACTIVITY -> Captured(textOf(GetCurrentActivityTool().execute(arguments, context)))

        Section.UI -> Captured(renderUi(arguments, device))

        Section.LOGCAT -> {
            val read = with(LogcatReader) {
                LogcatReader.read(
                    device = device.device,
                    packageName = arguments.logcatPackage(context),
                    minLevel = arguments.logcatLevel(),
                    // Deliberately not LogcatReader.logcatMaxLines: that helper reads the
                    // `maxLines` argument android_get_logcat declares, and this tool's
                    // argument is `maxLogcatLines` — sharing it would silently ignore the cap.
                    maxLines = arguments.optionalInt("maxLogcatLines", DEFAULT_LOGCAT_LINES)
                        .coerceIn(1, MAX_LOGCAT_LINES),
                )
            }
            Captured(read.textOrExplanation())
        }

        Section.SCREENSHOT -> {
            val result = TakeScreenshotTool().execute(arguments, context)
            val image = result.content.filterIsInstance<ToolContent.Image>().firstOrNull()
            when (image) {
                null -> Captured(textOf(result).ifBlank { "The screenshot could not be captured." })
                else -> Captured("Attached as an image alongside this text.", image)
            }
        }
    }

    /**
     * The framework note is always emitted, never only on failure: an agent that does not know
     * this screen is Compose without `testTagsAsResourceId` will keep matching on test tags
     * that cannot exist, and blame the app rather than the opt-in it is missing.
     */
    private fun renderUi(arguments: JsonObject, device: spock.adb.device.ConnectedDevice): String {
        val depth = arguments.optionalInt("maxUiDepth", DEFAULT_UI_DEPTH).coerceIn(1, MAX_UI_DEPTH)
        val tree = UiTreeReader.read(device.device)
        return with(UiTreeReader) {
            val root = tree.root ?: return@with tree.frameworkNote() + "\n\nThe dump contained no UI nodes."
            tree.frameworkNote() + "\n\n" + root.render(maxDepth = depth)
        }
    }

    /** Unknown names are ignored rather than fatal, so a newer client cannot break an older plugin. */
    private fun resolveSections(arguments: JsonObject): List<Section> {
        val requested = arguments.optionalStringList("include") ?: return DEFAULT_SECTIONS
        if (requested.isEmpty()) return DEFAULT_SECTIONS
        val wanted = requested.map { it.trim().lowercase() }.toSet()
        return Section.ALL.filter { it.id in wanted }
    }

    private fun textOf(result: ToolResult): String =
        result.content.filterIsInstance<ToolContent.Text>()
            .joinToString("\n") { it.text }
            .ifBlank { "Nothing was reported." }

    private data class Captured(val text: String, val image: ToolContent.Image? = null)

    /** Ordered as a developer reads a bug report: where am I, what is drawn, what went wrong. */
    private enum class Section(val id: String, val heading: String) {
        ACTIVITY("activity", "Current activity"),
        UI("ui", "UI semantics tree"),
        LOGCAT("logcat", "Recent logcat"),
        SCREENSHOT("screenshot", "Screenshot"),
        ;

        companion object {
            /**
             * Listed explicitly rather than via `entries`, whose backing stdlib class is newer
             * than the one bundled with the oldest supported IDE. See docs/COMPATIBILITY.md.
             */
            val ALL: List<Section> = listOf(ACTIVITY, UI, LOGCAT, SCREENSHOT)
        }
    }

    private companion object {
        val DEFAULT_SECTIONS = listOf(Section.ACTIVITY, Section.UI, Section.LOGCAT)

        const val BUDGET_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L

        const val FORMAT_SUMMARY = "summary"
        const val FORMAT_FULL = "full"
        const val MAX_SCREENSHOT_NOTE_CHARS = 300

        const val DEFAULT_LOGCAT_LINES = 200
        const val MAX_LOGCAT_LINES = 2_000
        const val DEFAULT_UI_DEPTH = 25
        const val MAX_UI_DEPTH = 200

        /** This is the heaviest tool in the registry; the bundle still has to fit a context window. */
        const val MAX_CHARS = 120_000
    }
}
