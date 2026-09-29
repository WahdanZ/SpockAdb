package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.CancellationSignal
import spock.adb.device.ConnectedDevice
import spock.adb.device.ops.UiTreeOperations
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.NodeVisibility
import spock.adb.uitree.Presence
import spock.adb.uitree.UiCaptureException
import spock.adb.uitree.UiFramework
import spock.adb.uitree.UiNode
import spock.adb.uitree.UiObservation
import spock.adb.uitree.UiSelector
import spock.adb.uitree.UiTree
import spock.adb.uitree.UiTreeSearch
import spock.adb.uitree.ViewportVisibility

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

    /**
     * The capture itself is [UiTreeOperations], shared with the UI Inspector tab. What stays
     * here is the part that is about talking to an agent: how a tree and a framework are
     * described to one.
     *
     * The serial named in the observation is the one the agent chose, read from [device]'s
     * resolved metadata rather than from ddmlib.
     *
     * @param metrics display metrics an earlier capture in the same call already read; see
     *   [UiTreeOperations.observe].
     * @param timeoutSeconds for each command of the capture; a wait passes what it has left.
     * @param cancellation what stops the capture; by default this thread's interrupt.
     * @throws IllegalStateException with an actionable message when the dump fails. A lost
     *   device is told what an agent can do about it, which a person in the Inspector cannot.
     */
    fun read(
        device: ConnectedDevice,
        metrics: DisplayMetrics? = null,
        timeoutSeconds: Long = UiTreeOperations.DUMP_TIMEOUT_SECONDS,
        cancellation: CancellationSignal = CancellationSignal.currentThread(),
    ): UiObservation = try {
        UiTreeOperations(device.device, timeoutSeconds, device.serialNumber, cancellation).observe(metrics)
    } catch (e: UiCaptureException) {
        throw when (e.kind) {
            UiCaptureException.Kind.DEVICE_UNAVAILABLE ->
                e.withAdvice("Call android_list_devices to see which devices are connected.")
            else -> e
        }
    }

    /**
     * What every result built on a capture starts with: the summary line and, when the result
     * makes a claim about the screen, the limits line. Two lines, so the cost stays small.
     */
    fun UiObservation.preface(withLimits: Boolean = true): String =
        if (withLimits) summary() + "\n" + limitsNote() else summary()

    /** Guidance an agent needs before it starts matching elements on this screen. */
    fun UiTree.frameworkNote(): String = buildString {
        append("UI framework: ").append(framework.description)
        when (testTagSupport) {
            UiTree.TestTagSupport.AVAILABLE ->
                append("\nCompose test tags are visible, so prefer matching on testTag.")
            UiTree.TestTagSupport.UNAVAILABLE ->
                append(
                    "\nNo exposed Compose test tags were observed in this capture. Add tags and enable " +
                        "`Modifier.semantics { testTagsAsResourceId = true }` on their subtree if needed. " +
                        "Match on text or contentDescription when tags are unavailable.",
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

    /**
     * @param maxDepth how deep to descend before summarising. A whole tree is often thousands
     *   of nodes, and an agent pays for every one of them, so callers assembling a bundle can
     *   trade depth for tokens. Hidden subtrees are counted rather than dropped silently.
     * @param visibility each node's place in the viewport. Only a node not plainly in it gets a
     *   marker, such as `[outside viewport]` or `[62% in viewport]`, so a screen that is all in
     *   view costs nothing extra.
     * @param withCenter give each enabled clickable node `center=[x,y]`, the point
     *   `android_tap_element` would tap.
     */
    fun UiNode.render(
        depth: Int = 0,
        maxDepth: Int = Int.MAX_VALUE,
        visibility: Map<UiNode, NodeVisibility>? = null,
        withCenter: Boolean = false,
    ): String = buildString {
        append("  ".repeat(depth))
        append(line(visibility, withCenter))

        when {
            children.isEmpty() -> Unit
            depth < maxDepth -> children.forEach {
                append('\n').append(it.render(depth + 1, maxDepth, visibility, withCenter))
            }
            else -> {
                val hidden = children.sumOf { it.asSequence().count() }
                append('\n').append("  ".repeat(depth + 1))
                append("[").append(hidden).append(" more node(s) below this one, hidden by maxUiDepth=")
                append(maxDepth).append(". Raise it, or call android_get_ui_tree for the full tree.]")
            }
        }
    }

    /**
     * This node alone, on one line and without its children, for a flat listing where a node's
     * descendants are listed in their own right. See [render] for the parameters.
     */
    fun UiNode.line(
        visibility: Map<UiNode, NodeVisibility>? = null,
        withCenter: Boolean = false,
    ): String = buildString {
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
        append(centerNote(withCenter, visibility))
        visibility?.get(this@line)?.marker()?.let { append(" [").append(it).append(']') }
    }

    private fun UiNode.shortClassName(): String = className.substringAfterLast('.')

    /** ` center=[x,y]` when asked for and there is a [tapRegion], so [line] stays one line per field. */
    private fun UiNode.centerNote(withCenter: Boolean, visibility: Map<UiNode, NodeVisibility>?): String =
        if (withCenter) tapRegion(visibility)?.let { " center=[${it.centerX},${it.centerY}]" }.orEmpty() else ""

    /**
     * The region whose centre `android_tap_element` taps: the part in view, so a control half
     * scrolled out of its list is not pressed under the list's edge. Without a viewport it is the
     * whole bounds, as it is for the tap. Null for anything the element tools would not press
     * there: a node that is not clickable, only scrollable or checkable (a tap lands on its
     * nearest clickable ancestor instead), disabled (they refuse it), or with nothing in view.
     */
    private fun UiNode.tapRegion(visibility: Map<UiNode, NodeVisibility>?): UiNode.Bounds? {
        if (!isTapTarget || !bounds.hasArea) return null
        val seen = visibility?.get(this) ?: return bounds
        return when (seen.presence) {
            Presence.VIEWPORT_UNKNOWN -> bounds
            Presence.IN_VIEWPORT, Presence.PARTIALLY_IN_VIEWPORT -> seen.visibleRegion
            Presence.OUTSIDE_VIEWPORT, Presence.ZERO_AREA -> null
        }
    }

    /** Enabled, and a tap lands on it rather than on an ancestor. */
    private val UiNode.isTapTarget: Boolean
        get() = clickable && enabled

    /** Resolves the selector arguments every element tool accepts. */
    fun JsonObject.toSelector(interactiveOnly: Boolean = false) = UiSelector(
        testTag = optionalString("testTag"),
        text = optionalString("text"),
        contentDescription = optionalString("contentDescription"),
        exact = optionalBoolean("exact", false),
        interactiveOnly = interactiveOnly,
        packageName = optionalString("packageName"),
        exactTag = optionalBoolean("exactTag", false),
        containerTag = optionalString("containerTag"),
    )

    fun Schema.ObjectBuilder.elementSelector() {
        string("testTag", "Compose Modifier.testTag, or a View resource id. The most reliable match.")
        string("text", "Visible text of the element.")
        string("contentDescription", "Accessibility content description.")
        boolean("exact", "Match the whole value rather than a substring. Defaults to false.")
        string("packageName", "Restrict matches to this application package.")
        string("containerTag", "Exact tag or resource ID of one container to search within.")
        boolean("exactTag", "Case-sensitive whole tag or resource ID match. Defaults to false for compatibility.")
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
            "guessing coordinates from a screenshot. On a Compose screen this is the composable " +
            "tree as Compose publishes it: merged semantics, with Modifier.testTag values as testTag. " +
            "Enabled clickable nodes carry center=[x,y], the point android_tap_element taps (the centre of " +
            "the part in view); prefer the element tools over tapping coordinates."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        boolean("interactiveOnly", "List only elements that can be acted on. Defaults to false.")
        boolean(
            "meaningfulOnly",
            "List elements that can be acted on, plus elements with text or a content description, in " +
                "screen order, so each control keeps the label next to it. Takes precedence over " +
                "interactiveOnly. Defaults to false.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val observation = UiTreeReader.read(context.requireDevice(arguments.optionalString("deviceSerial")))
        val tree = observation.tree
        val visibility = ViewportVisibility.classifyAll(observation)

        with(UiTreeReader) {
            val root = tree.root
                ?: return ToolResult.error(observation.preface() + "\nThe dump contained no UI nodes.")
            val listing = when {
                arguments.optionalBoolean("meaningfulOnly", false) -> list(
                    "Interactive and labelled elements:",
                    tree.nodes().filter { it.bounds.hasArea && (it.isInteractive || it.isLabelled) },
                    visibility,
                )
                arguments.optionalBoolean("interactiveOnly", false) -> list(
                    "Interactive elements:",
                    tree.nodes().filter { it.isInteractive && it.bounds.hasArea },
                    visibility,
                )
                else -> root.render(visibility = visibility, withCenter = true)
            }
            return ToolResult.text(observation.preface() + "\n" + tree.frameworkNote() + "\n\n" + listing)
        }
    }

    private val UiNode.isLabelled: Boolean
        get() = text.isNotBlank() || contentDescription.isNotBlank()

    /**
     * A flat listing under [heading], in screen order, each node on its own line with its tap
     * point. Never the subtree: a matched descendant is listed in its own right, and only once.
     */
    private fun list(heading: String, nodes: Sequence<UiNode>, visibility: Map<UiNode, NodeVisibility>): String =
        with(UiTreeReader) {
            heading + "\n" + nodes.joinToString("\n") { "  " + it.line(visibility, withCenter = true) }
        }
}

/** `android_find_ui_element` — locate without acting. */
class FindUiElementTool : AdbTool {
    override val name = "android_find_ui_element"
    override val description =
        "Find elements on screen by test tag, text or content description, and report what " +
            "was matched including bounds, whether it is enabled, and whether it is in the viewport. " +
            "Use it to check an element exists before acting, or to disambiguate when several match. " +
            "To find a composable by its Modifier.testTag, pass testTag with exactTag=true."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { with(UiTreeReader) { elementSelector() } }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val selector = with(UiTreeReader) { arguments.toSelector() }
        if (selector.isEmpty) {
            return ToolResult.error("Give at least one of testTag, text or contentDescription.")
        }

        val observation = UiTreeReader.read(device)
        val tree = observation.tree
        val matches = UiTreeSearch.findAll(tree, selector)

        return with(UiTreeReader) {
            when {
                matches.isEmpty() -> ToolResult.text(
                    observation.preface() + "\nNo element matched ${selector.describe()}.\n\n" +
                        tree.frameworkNote(),
                )
                else -> {
                    val visibility = ViewportVisibility.classifyAll(observation)
                    ToolResult.text(
                        observation.preface() + "\n${matches.size} match(es) for ${selector.describe()}:\n" +
                            matches.joinToString("\n") {
                                "  " + it.render() + "\n    visibility: " + visibility.getValue(it).describe()
                            },
                    )
                }
            }
        }
    }
}
