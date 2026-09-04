package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.uitree.UiFramework
import spock.adb.uitree.UiNode
import spock.adb.uitree.UiSelector
import spock.adb.uitree.UiTree
import spock.adb.uitree.UiTreeParser
import spock.adb.uitree.UiTreeSearch

/**
 * Semantics-first UI tools, which is what makes Compose work.
 *
 * Compose has no View hierarchy to inspect, so `Activity → View hierarchy` is simply the
 * wrong model for a Compose screen. What Compose *does* publish is semantics into the
 * accessibility tree — the same tree `uiautomator` reads — so these tools work identically
 * for Views, Compose and hybrid screens, and the plugin never depends on a Compose artifact
 * or pins a Compose version.
 */
internal object UiTreeReader {

    private const val DUMP_PATH = "/sdcard/spock-adb-ui-dump.xml"
    private const val DUMP_TIMEOUT_SECONDS = 30L
    private const val DUMP_MAX_CHARS = 400_000

    /** @throws IllegalStateException with an actionable message when the dump fails. */
    fun read(device: IDevice): UiTree {
        val dumpOutput = McpShell.run(device, "uiautomator dump $DUMP_PATH", timeoutSeconds = DUMP_TIMEOUT_SECONDS)
        check(!dumpOutput.contains("ERROR", ignoreCase = true)) {
            "uiautomator could not dump the UI: $dumpOutput. This happens when the screen is " +
                "off, a secure window is showing, or the UI is still animating."
        }

        val xml = McpShell.run(device, "cat ${ShellQuote.quote(DUMP_PATH)}", maxChars = DUMP_MAX_CHARS)
        runCatching { McpShell.run(device, "rm -f ${ShellQuote.quote(DUMP_PATH)}") }

        check(xml.isNotBlank()) { "uiautomator produced an empty dump." }
        return UiTreeParser.parse(xml)
    }

    /** Guidance an agent needs before it starts matching elements on this screen. */
    fun UiTree.frameworkNote(): String = buildString {
        append("UI framework: ").append(framework.description)
        when (testTagSupport) {
            UiTree.TestTagSupport.AVAILABLE ->
                append("\nCompose test tags are visible, so prefer matching on testTag.")
            UiTree.TestTagSupport.UNAVAILABLE ->
                append(
                    "\nCompose test tags are NOT visible on this screen. The app has not set " +
                        "`Modifier.semantics { testTagsAsResourceId = true }`, so testTag cannot be " +
                        "read over ADB. Match on text or contentDescription instead.",
                )
            UiTree.TestTagSupport.NOT_APPLICABLE -> Unit
        }
        if (framework == UiFramework.COMPOSE || framework == UiFramework.HYBRID) {
            append(
                "\nThis screen is Compose: do not assume a View hierarchy, and prefer semantic " +
                    "matching over coordinates.",
            )
        }
    }

    fun UiNode.render(depth: Int = 0): String = buildString {
        append("  ".repeat(depth))
        append(shortClassName())
        testTag?.let { append(" testTag=").append(it) }
        text.takeIf { it.isNotBlank() }?.let { append(" text=\"").append(it).append('"') }
        contentDescription.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(it).append('"') }
        if (clickable) append(" clickable")
        if (scrollable) append(" scrollable")
        if (checkable) append(" checked=").append(checked)
        if (!enabled) append(" DISABLED")
        if (selected) append(" selected")
        append(' ').append(bounds)
        children.forEach { append('\n').append(it.render(depth + 1)) }
    }

    private fun UiNode.shortClassName(): String = className.substringAfterLast('.')

    /** Resolves the selector arguments every element tool accepts. */
    fun JsonObject.toSelector(interactiveOnly: Boolean = false) = UiSelector(
        testTag = optionalString("testTag"),
        text = optionalString("text"),
        contentDescription = optionalString("contentDescription"),
        exact = optionalBoolean("exact", false),
        interactiveOnly = interactiveOnly,
    )

    fun Schema.ObjectBuilder.elementSelector() {
        string("testTag", "Compose Modifier.testTag, or a View resource id. The most reliable match.")
        string("text", "Visible text of the element.")
        string("contentDescription", "Accessibility content description.")
        boolean("exact", "Match the whole value rather than a substring. Defaults to false.")
        deviceSerial()
    }
}

/** `android_get_ui_tree` — the semantics tree, with the framework identified. */
class GetUiTreeTool : AdbTool {
    override val name = "android_get_ui_tree"
    override val description =
        "The UI currently on screen as a structured semantics tree, and whether it is built " +
            "with Views, Jetpack Compose, or both. Each node reports test tag, text, content " +
            "description, bounds and whether it is clickable, enabled, scrollable, checked or " +
            "selected. Use this before interacting: match elements semantically rather than " +
            "guessing coordinates from a screenshot."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        boolean("interactiveOnly", "List only elements that can be acted on. Defaults to false.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val tree = UiTreeReader.read(device)
        val root = tree.root ?: return ToolResult.error("The dump contained no UI nodes.")

        with(UiTreeReader) {
            if (arguments.optionalBoolean("interactiveOnly", false)) {
                val interactive = tree.nodes().filter { it.isInteractive && it.bounds.isVisible }.toList()
                return ToolResult.text(
                    tree.frameworkNote() + "\n\nInteractive elements:\n" +
                        interactive.joinToString("\n") { "  " + it.render() },
                )
            }
            return ToolResult.text(tree.frameworkNote() + "\n\n" + root.render())
        }
    }
}

/** `android_find_ui_element` — locate without acting. */
class FindUiElementTool : AdbTool {
    override val name = "android_find_ui_element"
    override val description =
        "Find elements on screen by test tag, text or content description, and report what " +
            "was matched including bounds and whether it is enabled. Use it to check an " +
            "element exists before acting, or to disambiguate when several match."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { with(UiTreeReader) { elementSelector() } }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val selector = with(UiTreeReader) { arguments.toSelector() }
        if (selector.isEmpty) {
            return ToolResult.error("Give at least one of testTag, text or contentDescription.")
        }

        val tree = UiTreeReader.read(device)
        val matches = UiTreeSearch.findAll(tree, selector)

        return with(UiTreeReader) {
            when {
                matches.isEmpty() -> ToolResult.text(
                    "No element matched ${selector.describe()}.\n\n" + tree.frameworkNote(),
                )
                else -> ToolResult.text(
                    "${matches.size} match(es) for ${selector.describe()}:\n" +
                        matches.joinToString("\n") { "  " + it.render() },
                )
            }
        }
    }
}
