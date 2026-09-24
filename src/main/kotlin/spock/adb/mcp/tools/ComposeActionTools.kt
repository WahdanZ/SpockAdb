package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.mcp.tools.UiTreeReader.elementSelector
import spock.adb.mcp.tools.UiTreeReader.frameworkNote
import spock.adb.mcp.tools.UiTreeReader.preface
import spock.adb.mcp.tools.UiTreeReader.toSelector
import spock.adb.uitree.AccessibilityAudit
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.UiNode
import spock.adb.uitree.UiObservation
import spock.adb.uitree.UiSelector
import spock.adb.uitree.UiTreeSearch

/**
 * Element-addressed interaction and assertions.
 *
 * These exist so an agent never has to compute a coordinate. A tap derived from a screenshot
 * breaks on a different screen size, density or font scale, and is the main reason
 * AI-driven UI automation is flaky. Every tool here resolves the element from semantics and
 * only then derives the tap point from the matched node's own bounds.
 *
 * A refusal starts with the observation's summary too, so "no match" or "ambiguous" says which
 * window on which device it was decided against.
 */
private fun ToolContext.resolveElement(
    arguments: JsonObject,
    action: UiTreeSearch.Action,
): Pair<UiObservation, UiNode> {
    val device = requireDevice(arguments.optionalString("deviceSerial"))
    val selector = arguments.toSelector()
    require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

    val observation = UiTreeReader.read(device)
    val tree = observation.tree
    val match = observation.refusing { UiTreeSearch.findUnique(tree, selector, action) }
        ?: throw IllegalStateException(
            observation.summary() + "\nNo element matched ${selector.describe()}. " + tree.frameworkNote() +
                " Call android_get_ui_tree to see what is actually on screen.",
        )

    // Compose usually puts text on a child and the click handler on its parent, so the node
    // carrying the text is often not the one that can be tapped.
    val target = observation.refusing { UiTreeSearch.actionTarget(tree, match, action, selector) }
    return observation to target
}

/** Runs a selection step, prefixing a refusal (ambiguous, disabled, out of scope) with [this] summary. */
private inline fun <T> UiObservation.refusing(select: () -> T): T = try {
    select()
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException(summary() + "\n" + e.message, e)
}

/** `android_tap_element` — tap by semantics, not coordinates. */
class TapElementTool : AdbTool {
    override val name = "android_tap_element"
    override val description =
        "Tap an element identified by test tag, text or content description. Prefer this over " +
            "android_tap: coordinates guessed from a screenshot break on a different screen " +
            "size, density or font scale. Resolves the tappable parent automatically, which " +
            "Compose needs because the click handler usually sits above the text."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj { elementSelector() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val (observation, target) = context.resolveElement(arguments, UiTreeSearch.Action.TAP)
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        McpShell.run(device, "input tap ${target.bounds.centerX} ${target.bounds.centerY}")
        return ToolResult.text(
            observation.summary() +
                "\nTap dispatched to '${target.label}' at ${target.bounds}; UI outcome not verified.",
        )
    }
}

/** `android_long_press_element`. */
class LongPressElementTool : AdbTool {
    override val name = "android_long_press_element"
    override val description =
        "Long-press an element identified by test tag, text or content description."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        elementSelector()
        integer("durationMs", "Press duration in milliseconds. Defaults to 800.")
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val (observation, target) = context.resolveElement(arguments, UiTreeSearch.Action.LONG_PRESS)
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val duration = arguments.optionalInt("durationMs", DEFAULT_LONG_PRESS_MS)
        require(duration in 1..MAX_LONG_PRESS_MS) { "durationMs must be between 1 and $MAX_LONG_PRESS_MS." }

        // A swipe that starts and ends at the same point is a long press.
        val x = target.bounds.centerX
        val y = target.bounds.centerY
        McpShell.run(device, "input swipe $x $y $x $y $duration")
        return ToolResult.text(
            observation.summary() +
                "\nLong press dispatched to '${target.label}' for ${duration}ms; UI outcome not verified.",
        )
    }

    private companion object {
        const val DEFAULT_LONG_PRESS_MS = 800
        const val MAX_LONG_PRESS_MS = 10_000
    }
}

/** `android_scroll_to_element` — scroll until the element is on screen. */
class ScrollToElementTool : AdbTool {
    override val name = "android_scroll_to_element"
    override val description =
        "Scroll the screen until an element becomes visible, then report where it is. Use " +
            "this before tapping something that is below the fold — an element that is not " +
            "in the semantics tree cannot be tapped."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        elementSelector()
        integer("maxSwipes", "How many scroll attempts to make. Defaults to 8.")
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val connected = context.requireDevice(arguments.optionalString("deviceSerial"))
        val device = connected.device
        val selector = arguments.toSelector()
        require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

        val maxSwipes = arguments.optionalInt("maxSwipes", DEFAULT_MAX_SWIPES).coerceIn(1, MAX_SWIPES)

        // Measured once: a swipe does not change the display, and each measurement is two commands.
        var metrics: DisplayMetrics? = null
        repeat(maxSwipes) { attempt ->
            val observation = UiTreeReader.read(connected, metrics)
            metrics = observation.metrics
            val tree = observation.tree
            UiTreeSearch.findOne(tree, selector)?.let { found ->
                return ToolResult.text(
                    observation.preface() +
                        "\nFound '${found.label}' after $attempt scroll(s) at ${found.bounds}.",
                )
            }

            val scrollable = UiTreeSearch.scrollTarget(tree, selector)
                ?: return ToolResult.error(
                    observation.preface() +
                        "\nNo element matched ${selector.describe()} and nothing on screen is scrollable.",
                )

            // Swipe within the scrollable container's own bounds, inset from the edges so the
            // gesture is not captured as a system edge swipe.
            val x = scrollable.bounds.centerX
            val top = scrollable.bounds.top + scrollable.bounds.height / SWIPE_INSET
            val bottom = scrollable.bounds.bottom - scrollable.bounds.height / SWIPE_INSET
            McpShell.run(device, "input swipe $x $bottom $x $top $SWIPE_DURATION_MS")
        }

        return ToolResult.error(
            "No element matched ${selector.describe()} after $maxSwipes scroll(s).",
        )
    }

    private companion object {
        const val DEFAULT_MAX_SWIPES = 8
        const val MAX_SWIPES = 30
        const val SWIPE_INSET = 4
        const val SWIPE_DURATION_MS = 300
    }
}

/** `android_input_text_into_element` — focus then type. */
class InputTextIntoElementTool : AdbTool {
    override val name = "android_input_text_into_element"
    override val description =
        "Tap a text field identified by test tag, text or content description, then type into " +
            "it. Use this instead of android_input_text, which types into whatever happens to " +
            "be focused."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("value", "The text to type.", required = true)
        elementSelector()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // Read before resolving the element: the tool is also reachable from callers that do
        // not go through McpProtocol's argument check, and failing on the element would
        // misreport a caller that simply omitted the text.
        val value = arguments.requiredString("value")
        val (observation, target) = context.resolveElement(arguments, UiTreeSearch.Action.TEXT_INPUT)
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))

        McpShell.run(device, "input tap ${target.bounds.centerX} ${target.bounds.centerY}")
        McpShell.run(device, "input text ${ShellQuote.quote(value)}")
        return ToolResult.text(
            observation.summary() +
                "\nText input dispatched to '${target.label}'; focus and resulting text not verified.",
        )
    }
}

/** Assertions, so an agent can verify rather than infer from pixels. */
class AssertVisibleTool : AdbTool {
    override val name = "android_assert_visible"
    override val description =
        "Check that an element is present and visible. Returns an error result when it is " +
            "not, so a test workflow can stop at the first failure."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { elementSelector() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val selector = arguments.toSelector()
        require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

        val observation = UiTreeReader.read(device)
        val match = UiTreeSearch.findOne(observation.tree, selector)
        return when {
            match != null -> ToolResult.text(
                observation.preface() + "\nPASS: '${match.label}' is visible at ${match.bounds}.",
            )
            else -> ToolResult.error(
                observation.preface() + "\nFAIL: nothing matched ${selector.describe()}.\n\n" +
                    observation.tree.frameworkNote(),
            )
        }
    }
}

class AssertEnabledTool : AdbTool {
    override val name = "android_assert_enabled"
    override val description = "Check that an element is present and enabled."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { elementSelector() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val selector = arguments.toSelector()
        require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

        val observation = UiTreeReader.read(device)
        val match = UiTreeSearch.findOne(observation.tree, selector)
            ?: return ToolResult.error(observation.preface() + "\nFAIL: nothing matched ${selector.describe()}.")

        return when {
            match.enabled -> ToolResult.text(observation.preface() + "\nPASS: '${match.label}' is enabled.")
            else -> ToolResult.error(observation.preface() + "\nFAIL: '${match.label}' is present but disabled.")
        }
    }
}

class AssertTextTool : AdbTool {
    override val name = "android_assert_text"
    override val description =
        "Check that the given text appears somewhere on screen. Use it to verify the result " +
            "of an action rather than inferring it from a screenshot."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("text", "The text that should be on screen.", required = true)
        boolean("exact", "Match the whole value rather than a substring. Defaults to false.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val expected = arguments.requiredString("text")
        val exact = arguments.optionalBoolean("exact", false)

        val observation = UiTreeReader.read(device)
        val tree = observation.tree
        // Content description counts: Compose text is often exposed that way.
        val match = UiTreeSearch.findOne(tree, UiSelector(text = expected, exact = exact))
            ?: UiTreeSearch.findOne(tree, UiSelector(contentDescription = expected, exact = exact))

        return when {
            match != null -> ToolResult.text(observation.preface() + "\nPASS: found '$expected' at ${match.bounds}.")
            else -> {
                val visible = tree.nodes()
                    .mapNotNull { it.text.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(VISIBLE_TEXT_SAMPLE)
                    .toList()
                ToolResult.error(
                    observation.preface() + "\nFAIL: '$expected' is not on screen. Visible text: " +
                        visible.joinToString(", ") { "\"$it\"" },
                )
            }
        }
    }

    private companion object {
        const val VISIBLE_TEXT_SAMPLE = 40
    }
}

/** `android_accessibility_audit` — findings with Compose-level fixes. */
class AccessibilityAuditTool : AdbTool {
    override val name = "android_accessibility_audit"
    override val description =
        "Audit the current screen for accessibility problems: unlabelled interactive " +
            "elements, touch targets below the recommended minimum, ambiguous duplicate " +
            "labels and unlabelled images. Each finding comes with a code-level fix " +
            "appropriate to the framework in use."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // The observation carries the density its touch-target estimates are made at.
        val observation = UiTreeReader.read(context.requireDevice(arguments.optionalString("deviceSerial")))
        val tree = observation.tree
        val findings = AccessibilityAudit.audit(tree)

        val coverage = AccessibilityAudit.coverageNote(tree)
        val preface = observation.preface() + "\n" + tree.frameworkNote()
        if (findings.isEmpty()) {
            return ToolResult.text(preface + "\n\nNo issues detected by these checks.\n" + coverage)
        }
        return ToolResult.text(
            preface + "\n\n${findings.size} finding(s):\n\n" +
                findings.joinToString("\n\n") { it.describe(tree.framework) } + "\n\n" + coverage,
        )
    }
}
