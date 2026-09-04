package spock.adb.mcp.tools

import com.google.gson.JsonObject

/**
 * `android_get_debug_context` — the whole triage bundle in one call.
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
        "Everything needed to triage what is on screen right now, in one call: the current " +
            "activity, the UI semantics tree with its framework identified, recent logcat, and " +
            "optionally a screenshot. Prefer this over calling android_get_current_activity, " +
            "android_get_ui_tree and android_get_logcat separately — it is one round-trip and " +
            "every section describes the same moment. Start here when asked why a screen looks " +
            "wrong, why an app crashed, or what state the app is in."

    override val safety = ToolSafety.READ_ONLY

    override val inputSchema: JsonObject = Schema.obj {
        stringArray(
            "include",
            "Which sections to capture. Defaults to activity, ui and logcat. Add \"screenshot\" " +
                "only when you need to see the screen rather than read its structure — it is by " +
                "far the most expensive section.",
            values = Section.ALL.map { it.id },
        )
        string(
            "packageName",
            "Package for the logcat section. Defaults to the open project's application ID. " +
                "Pass an empty string to read the whole log.",
        )
        enumeration(
            "minLevel",
            "Minimum logcat level. Defaults to V; use E when hunting a crash.",
            listOf("V", "D", "I", "W", "E", "F"),
        )
        integer("maxLogcatLines", "Logcat lines to include. Defaults to 200, capped at 2000.")
        integer("maxUiDepth", "How deep to render the UI tree. Defaults to 25.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
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

        const val DEFAULT_LOGCAT_LINES = 200
        const val MAX_LOGCAT_LINES = 2_000
        const val DEFAULT_UI_DEPTH = 25
        const val MAX_UI_DEPTH = 200

        /** This is the heaviest tool in the registry; the bundle still has to fit a context window. */
        const val MAX_CHARS = 120_000
    }
}
