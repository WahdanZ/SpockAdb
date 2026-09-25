package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.mcp.tools.ElementActions.expectation
import spock.adb.mcp.tools.UiTreeReader.elementSelector
import spock.adb.mcp.tools.UiTreeReader.frameworkNote
import spock.adb.mcp.tools.UiTreeReader.preface
import spock.adb.mcp.tools.UiTreeReader.toSelector
import spock.adb.uitree.AccessibilityAudit
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.NodeVisibility
import spock.adb.uitree.Presence
import spock.adb.uitree.UiNode
import spock.adb.uitree.UiObservation
import spock.adb.uitree.UiSelector
import spock.adb.uitree.UiTreeSearch
import spock.adb.uitree.ViewportVisibility

/*
 * Element-addressed interaction and assertions.
 *
 * These exist so an agent never has to compute a coordinate. A tap derived from a screenshot
 * breaks on a different screen size, density or font scale, and is the main reason
 * AI-driven UI automation is flaky. Every tool here resolves the element from semantics and
 * only then derives the tap point from the matched node's own bounds. The three actions share
 * one path, [ElementActions], which also checks an expected result when one is given.
 */

/** `android_tap_element` — tap by semantics, not coordinates. */
class TapElementTool : AdbTool {
    override val name = "android_tap_element"
    override val description =
        "Tap an element identified by test tag, text or content description. Prefer this over " +
            "android_tap: coordinates guessed from a screenshot break on a different screen " +
            "size, density or font scale. Resolves the tappable parent automatically, which " +
            "Compose needs because the click handler usually sits above the text. $EXPECTATION_HINT"
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        elementSelector()
        expectation()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ElementActions.perform(
        context,
        arguments,
        ElementActions.Spec(UiTreeSearch.Action.TAP, "Tap", unverified = "UI outcome not verified.") { x, y ->
            listOf(ElementActions.Step("input tap $x $y", "the tap"))
        },
    )
}

/** `android_long_press_element`. */
class LongPressElementTool : AdbTool {
    override val name = "android_long_press_element"
    override val description =
        "Long-press an element identified by test tag, text or content description. $EXPECTATION_HINT"
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        elementSelector()
        integer("durationMs", "Press duration in milliseconds. Defaults to 800.")
        expectation()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val duration = arguments.optionalInt("durationMs", DEFAULT_LONG_PRESS_MS)
        require(duration in 1..MAX_LONG_PRESS_MS) { "durationMs must be between 1 and $MAX_LONG_PRESS_MS." }

        val spec = ElementActions.Spec(
            UiTreeSearch.Action.LONG_PRESS,
            "Long press",
            unverified = "UI outcome not verified.",
            details = " for ${duration}ms",
        ) { x, y ->
            // A swipe that starts and ends at the same point is a long press.
            listOf(ElementActions.Step("input swipe $x $y $x $y $duration", "the long press"))
        }
        return ElementActions.perform(context, arguments, spec)
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
        var seenOutside = false
        repeat(maxSwipes) { attempt ->
            val observation = UiTreeReader.read(connected, metrics)
            metrics = observation.metrics
            val tree = observation.tree
            val matches = UiTreeSearch.findAll(tree, selector)
            found(observation, matches)?.let { (node, visibility) ->
                return ToolResult.text(
                    observation.preface() +
                        "\nFound '${node.label}' after $attempt scroll(s) at ${node.bounds}, ${visibility.describe()}.",
                )
            }
            seenOutside = seenOutside || matches.isNotEmpty()

            val scrollable = UiTreeSearch.scrollTarget(tree, selector)
                ?: return ToolResult.error(
                    observation.preface() + "\n" + notFound(selector, matches) + " Nothing on screen is scrollable.",
                )

            // Swipe within the scrollable container's own bounds, inset from the edges so the
            // gesture is not captured as a system edge swipe.
            val x = scrollable.bounds.centerX
            val top = scrollable.bounds.top + scrollable.bounds.height / SWIPE_INSET
            val bottom = scrollable.bounds.bottom - scrollable.bounds.height / SWIPE_INSET
            McpShell.run(device, "input swipe $x $bottom $x $top $SWIPE_DURATION_MS")
        }

        val outside = if (seenOutside) " It was in the tree, but outside the viewport or its scroll container." else ""
        return ToolResult.error(
            "No element matched ${selector.describe()} in the viewport after $maxSwipes scroll(s).$outside",
        )
    }

    /**
     * The first match with something in the viewport. Without a viewport that cannot be told, so
     * the first match counts, as it did before viewports were known, and the description says so.
     */
    private fun found(observation: UiObservation, matches: List<UiNode>): Pair<UiNode, NodeVisibility>? {
        val visibility = ViewportVisibility.classifyAll(observation)
        return matches.map { it to visibility.getValue(it) }.firstOrNull { (_, seen) ->
            seen.inViewport || seen.presence == Presence.VIEWPORT_UNKNOWN
        }
    }

    private fun notFound(selector: UiSelector, matches: List<UiNode>): String = when {
        matches.isEmpty() -> "No element matched ${selector.describe()}."
        else -> "${matches.size} match(es) for ${selector.describe()}, all outside the viewport or their " +
            "scroll container."
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
            "be focused. $EXPECTATION_HINT"
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("value", "The text to type.", required = true)
        elementSelector()
        expectation()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // Read before resolving the element: the tool is also reachable from callers that do
        // not go through McpProtocol's argument check, and failing on the element would
        // misreport a caller that simply omitted the text.
        val value = arguments.requiredString("value")
        val spec = ElementActions.Spec(
            UiTreeSearch.Action.TEXT_INPUT,
            "Text input",
            unverified = "focus and resulting text not verified.",
        ) { x, y ->
            // Typed only once the focusing tap was sent: text sent without it lands wherever focus was.
            listOf(
                ElementActions.Step("input tap $x $y", "the focusing tap"),
                ElementActions.Step("input text ${ShellQuote.quote(value)}", "the text"),
            )
        }
        return ElementActions.perform(context, arguments, spec)
    }
}

/** How each action's description points to its optional check. */
private const val EXPECTATION_HINT =
    "Optionally name what should happen, with expectTestTag, expectText or expectContentDescription and " +
        "expectUntil, and the result says whether it was seen: VERIFIED, NOT OBSERVED, or INCONCLUSIVE when " +
        "it already held before. The input is sent once and never repeated, even when the check fails or " +
        "the dispatch is uncertain."

/**
 * The verdict of a visibility assertion over every match, not just the first: one match in the
 * viewport is enough to pass, and a pass says how many were.
 *
 * Null when nothing matched, which each assertion words its own way. Without a viewport, a match
 * cannot be said to be on screen or off it, so the verdict is inconclusive — an error result, since
 * a test workflow must not read it as a pass.
 */
private fun UiObservation.visibilityVerdict(matches: List<UiNode>, what: String): ToolResult? {
    if (matches.isEmpty()) return null
    val visibility = ViewportVisibility.classifyAll(this)
    val inView = matches.filter { visibility.getValue(it).inViewport }
    val first = inView.firstOrNull()
    return when {
        viewport == null -> ToolResult.error(
            preface() + "\nINCONCLUSIVE: $what is in the tree (${matches.size} match(es)), but the viewport is " +
                "unknown, so whether it is on screen was not checked.",
        )
        first != null -> ToolResult.text(
            // The description ends "(occlusion not checked)", so the pass never claims more than bounds show.
            preface() + "\nPASS: '${first.label}' at ${first.bounds} is ${visibility.getValue(first).describe()}. " +
                "${inView.size} of ${matches.size} match(es) in the viewport.",
        )
        else -> ToolResult.error(
            preface() + "\nFAIL: $what is present in the tree but outside the viewport or its scroll container " +
                "(${matches.size} match(es), first at ${matches.first().bounds}). Call android_scroll_to_element " +
                "to bring it into view.",
        )
    }
}

/** Assertions, so an agent can verify rather than infer from pixels. */
class AssertVisibleTool : AdbTool {
    override val name = "android_assert_visible"
    override val description =
        "Check that an element is present and in the viewport. Returns an error result when it is " +
            "not, or when the viewport is unknown, so a test workflow can stop at the first failure. " +
            "Bounds cannot show whether another element covers it."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { elementSelector() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val selector = arguments.toSelector()
        require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

        val observation = UiTreeReader.read(device)
        val matches = UiTreeSearch.findAll(observation.tree, selector)
        return observation.visibilityVerdict(matches, "an element matching ${selector.describe()}")
            ?: ToolResult.error(
                observation.preface() + "\nFAIL: nothing matched ${selector.describe()}. An element scrolled out " +
                    "of view is often left out of the capture entirely; android_scroll_to_element brings it in.\n\n" +
                    observation.tree.frameworkNote(),
            )
    }
}

class AssertEnabledTool : AdbTool {
    override val name = "android_assert_enabled"
    override val description =
        "Check that exactly one element matches and that it is enabled. Several matches fail, listing them, " +
            "rather than answering for whichever came first."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { elementSelector() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val selector = arguments.toSelector()
        require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

        val observation = UiTreeReader.read(device)
        // One element or none: with several, "enabled" would be answered for whichever came first.
        val match = try {
            UiTreeSearch.findUnique(observation.tree, selector)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(observation.preface() + "\nFAIL: ${e.message}")
        } ?: return ToolResult.error(observation.preface() + "\nFAIL: nothing matched ${selector.describe()}.")

        return when {
            match.enabled -> ToolResult.text(observation.preface() + "\nPASS: '${match.label}' is enabled.")
            else -> ToolResult.error(observation.preface() + "\nFAIL: '${match.label}' is present but disabled.")
        }
    }
}

class AssertTextTool : AdbTool {
    override val name = "android_assert_text"
    override val description =
        "Check that the given text is on screen: in the viewport, not only in the tree. Use it to " +
            "verify the result of an action rather than inferring it from a screenshot."
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
        val byText = UiTreeSearch.findAll(tree, UiSelector(text = expected, exact = exact))
        val byDescription = UiTreeSearch.findAll(tree, UiSelector(contentDescription = expected, exact = exact))
        val matches = byText + byDescription.filterNot { match -> byText.any { it === match } }

        return observation.visibilityVerdict(matches, "'$expected'") ?: run {
            // Only what is in the viewport is offered as what the screen shows, when that can be told.
            val visibility = ViewportVisibility.classifyAll(observation)
            val known = observation.viewport != null
            val shown = tree.nodes()
                .filter { !known || visibility.getValue(it).inViewport }
                .mapNotNull { it.text.takeIf(String::isNotBlank) }
                .distinct()
                .take(VISIBLE_TEXT_SAMPLE)
                .toList()
            ToolResult.error(
                observation.preface() + "\nFAIL: '$expected' is not on screen. " +
                    (if (known) "Text in the viewport: " else "Text in the capture: ") +
                    shown.joinToString(", ") { "\"$it\"" },
            )
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
        val findings = AccessibilityAudit.audit(observation)

        val coverage = AccessibilityAudit.coverageNote(observation)
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
