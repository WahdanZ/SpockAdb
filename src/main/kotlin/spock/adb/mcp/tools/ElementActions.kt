package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.CancellationSignal
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.UiTreeReader.frameworkNote
import spock.adb.mcp.tools.UiTreeReader.preface
import spock.adb.mcp.tools.UiTreeReader.toSelector
import spock.adb.mcp.tools.WaitReport.withNote
import spock.adb.uitree.NodeVisibility
import spock.adb.uitree.Presence
import spock.adb.uitree.UiCaptureException
import spock.adb.uitree.UiCaptureShell
import spock.adb.uitree.UiCondition
import spock.adb.uitree.UiNode
import spock.adb.uitree.UiObservation
import spock.adb.uitree.UiSelector
import spock.adb.uitree.UiTreeSearch
import spock.adb.uitree.UiWaiter
import spock.adb.uitree.ViewportVisibility
import spock.adb.uitree.WaitOutcome

/**
 * What an element action's result says about the UI after it. A dispatch that failed is not one
 * of these: it never got as far as a result to verify.
 */
enum class ActionOutcome(val isError: Boolean) {
    /** No expectation was given, so nothing was checked. */
    NOT_REQUESTED(isError = false),

    /** The expected state was not there before the action and was seen after it. */
    VERIFIED(isError = false),

    /** The expected state was not seen after the action, within the time given. */
    NOT_OBSERVED(isError = true),

    /** The expected state already held before the action, so seeing it after proves nothing. */
    INCONCLUSIVE(isError = true),

    /** Stopped while checking. The action itself was dispatched. */
    CANCELLED(isError = true),
    ;

    companion object {
        /** A state that already held before the action is no evidence the action caused it. */
        fun of(wait: WaitOutcome, heldBefore: Boolean): ActionOutcome = when (wait) {
            is WaitOutcome.Cancelled -> CANCELLED
            is WaitOutcome.Satisfied -> if (heldBefore) INCONCLUSIVE else VERIFIED
            is WaitOutcome.TimedOut, is WaitOutcome.CaptureFailed -> NOT_OBSERVED
        }
    }
}

/**
 * The one path tap, long press and text input take: observe, resolve one target, dispatch the
 * input once, and — when the agent said what should happen — look for it.
 *
 * A dispatch is never repeated. A shell call that failed may still have delivered its input, and
 * a second tap on "Place order" can order twice, so a failed step is reported as uncertain and
 * left for the agent to check; an expected result that was not seen is reported as not observed.
 * Neither is retried here.
 */
internal object ElementActions {

    /** One shell command of an action, and what it is called if it is the one that failed. */
    class Step(val command: String, val name: String) {
        /** `input text`, without the text: what a result names when the step was never sent. */
        val verb: String get() = command.split(' ').take(2).joinToString(" ")
    }

    /**
     * @param noun what the result says was dispatched: "Tap", "Long press".
     * @param details said after where it was dispatched, such as " for 800ms".
     * @param unverified what a result without an expectation says was not checked.
     * @param steps the shell commands, in order, for a press at (x, y).
     */
    class Spec(
        val action: UiTreeSearch.Action,
        val noun: String,
        val unverified: String,
        val details: String = "",
        val steps: (x: Int, y: Int) -> List<Step>,
    )

    fun perform(context: ToolContext, arguments: JsonObject, spec: Spec): ToolResult {
        // Taken first, on this thread: the default signal is this thread's interrupt.
        val signal = context.cancellationSignal()
        // An argument error is reported before the device is touched, let alone pressed.
        val expectation = Expectation.from(arguments, context.canCancel)
        val connected = context.requireDevice(arguments.optionalString("deviceSerial"))
        val resolved = try {
            resolveElement(connected, arguments, spec.action, signal)
        } catch (e: UiCaptureException) {
            // Stopped while looking for the target: the one moment a cancel can promise nothing was sent.
            if (e.kind != UiCaptureException.Kind.CANCELLED) throw e
            return ToolResult.error(
                "CANCELLED while finding ${arguments.toSelector().describe()}; nothing was dispatched to the device.",
            )
        }
        val before = expectation?.condition?.evaluate(resolved.observation)

        val dispatched = "${spec.noun} dispatched once to '${resolved.target.label}' ${resolved.where}${spec.details}"
        Dispatch(connected, signal, resolved, spec).send()?.let { return it }

        if (expectation == null || before == null) {
            return ToolResult.text(resolved.observation.summary() + "\n$dispatched; ${spec.unverified}")
        }
        val verdict = Verdict(resolved.observation, "$dispatched; it was not repeated.", spec.noun, expectation)
        return verdict.verify(connected, signal, before)
    }

    /** What the agent said should hold after the action. Its selector has no package or container scope. */
    class Expectation(val condition: UiCondition, val timeoutMs: Int, val requestedMs: Int) {
        val capped: Boolean get() = timeoutMs < requestedMs

        companion object {
            /**
             * Null when no expectation was asked for. A timeout or `expectUntil` without a selector
             * is refused, since it would be silently ignored.
             */
            fun from(arguments: JsonObject, canCancel: Boolean): Expectation? {
                val selector = UiSelector(
                    testTag = arguments.optionalString(TEST_TAG),
                    text = arguments.optionalString(TEXT),
                    contentDescription = arguments.optionalString(DESCRIPTION),
                    exact = arguments.optionalBoolean(EXACT, false),
                    exactTag = arguments.optionalBoolean(EXACT_TAG, false),
                )
                val until = arguments.optionalString(UNTIL)
                if (selector.isEmpty) {
                    val stray = listOf(UNTIL, TIMEOUT, EXACT, EXACT_TAG).filter { arguments.has(it) }
                    require(stray.isEmpty()) {
                        "${stray.joinToString()} given without $TEST_TAG, $TEXT or $DESCRIPTION, so there is " +
                            "nothing to check. Name the element expected after the action, or leave them out."
                    }
                    return null
                }
                val word = until ?: DEFAULT_UNTIL
                val condition = UntilArgument.parse(word, selector)
                    ?: throw IllegalArgumentException(
                        "Unknown $UNTIL '$word'. Use one of: ${UntilArgument.WORDS.joinToString()}.",
                    )
                val requested = arguments.optionalInt(TIMEOUT, DEFAULT_TIMEOUT_MS).coerceIn(0, MAX_TIMEOUT_MS)
                return Expectation(condition, WaitForElementTool.timeoutFor(requested, canCancel), requested)
            }
        }
    }

    /** The expectation arguments every element action accepts. Flat, as the schema builder has no nesting. */
    fun Schema.ObjectBuilder.expectation() {
        string(
            TEST_TAG,
            "Optional check after the action: the test tag or resource id of an element that should then be in " +
                "the state $UNTIL names. Any $TEST_TAG, $TEXT or $DESCRIPTION turns the check on. It is matched " +
                "over the whole screen: packageName and containerTag scope only the element acted on.",
        )
        string(TEXT, "Optional check after the action: text of the element expected.")
        string(DESCRIPTION, "Optional check after the action: content description of the element expected.")
        boolean(EXACT, "Whole-value match for $TEXT and $DESCRIPTION. Defaults to false.")
        boolean(EXACT_TAG, "Case-sensitive whole match for $TEST_TAG. Defaults to false.")
        enumeration(
            UNTIL,
            "What the expected element should be after the action, in android_wait_for_element's words. " +
                "Defaults to $DEFAULT_UNTIL.",
            UntilArgument.WORDS,
        )
        integer(
            TIMEOUT,
            "How long to look for the expected result, in milliseconds, 0 to $MAX_TIMEOUT_MS. Defaults to " +
                "$DEFAULT_TIMEOUT_MS; over HTTP at most ${WaitForElementTool.UNCANCELLABLE_MAX_TIMEOUT_MS}. The " +
                "first look always completes, even past this limit.",
        )
    }

    /** Sends each step once, in order, and stops at the first that fails or is cancelled. */
    private class Dispatch(
        private val connected: ConnectedDevice,
        private val signal: CancellationSignal,
        private val resolved: Resolved,
        private val spec: Spec,
    ) {
        private val shell = UiCaptureShell(
            connected.device,
            McpShell.DEFAULT_TIMEOUT_SECONDS,
            connected.serialNumber,
            signal,
            what = "Input",
        )

        /** Null when every step was sent; otherwise the error result that ends the call. */
        fun send(): ToolResult? {
            val steps = spec.steps(resolved.x, resolved.y)
            steps.forEachIndexed { index, step ->
                val unsent = unsentNote(steps.drop(index + 1))
                if (signal.isCancelled()) {
                    val sent = steps.take(index)
                    return error(
                        if (sent.isEmpty()) {
                            "CANCELLED before anything was sent to '${resolved.target.label}'; nothing was dispatched."
                        } else {
                            "CANCELLED after ${sent.joinToString { it.name }} was dispatched once, and not " +
                                "repeated.${unsentNote(steps.drop(index))}"
                        },
                    )
                }
                try {
                    shell.run(step.command)
                } catch (e: UiCaptureException) {
                    return error(
                        if (e.kind == UiCaptureException.Kind.CANCELLED) {
                            "CANCELLED during ${step.name} to '${resolved.target.label}'; the input may or may not " +
                                "have reached the device, and it was not repeated.$unsent"
                        } else {
                            "Dispatch uncertain at ${step.name} to '${resolved.target.label}': ${e.message} The " +
                                "input may have reached the device and was not repeated.$unsent Check with " +
                                "android_find_ui_element or android_wait_for_element before retrying."
                        },
                    )
                }
            }
            return null
        }

        private fun unsentNote(unsent: List<Step>): String =
            if (unsent.isEmpty()) "" else " ${unsent.joinToString { "`${it.verb}`" }} was never sent."

        private fun error(message: String) = ToolResult.error(resolved.observation.summary() + "\n" + message)
    }

    /** How a verification reads, given the pre-action [before] and what the wait saw. */
    private class Verdict(
        private val before: UiObservation,
        private val dispatched: String,
        noun: String,
        private val expectation: Expectation,
    ) {
        private val action = "the ${noun.lowercase()}"
        private val condition = expectation.condition
        private val expected = "${condition.selector.describe()} to be ${condition.expected}"

        /** Looks for the expectation after the input was sent, and says what was found. */
        fun verify(connected: ConnectedDevice, signal: CancellationSignal, heldBefore: UiCondition.Check): ToolResult {
            val waiter = UiWaiter(
                // The display does not change because of a tap, so the pre-action metrics are reused.
                capture = { seconds -> UiTreeReader.read(connected, before.metrics, seconds, signal) },
                signal = signal,
            )
            val outcome = waiter.await(condition, expectation.timeoutMs.toLong(), POLL_INTERVAL_MS)
            val result = report(outcome, heldBefore)
            if (!expectation.capped) return result
            return result.withNote(WaitReport.cappedNote(TIMEOUT, expectation.requestedMs))
        }

        private fun report(outcome: WaitOutcome, heldBefore: UiCondition.Check): ToolResult {
            val kind = ActionOutcome.of(outcome, heldBefore.satisfied)
            val took = WaitReport.took(outcome)
            val verdict = when (outcome) {
                is WaitOutcome.Satisfied -> satisfied(kind, outcome, took, heldBefore)
                is WaitOutcome.TimedOut -> notObserved(took, outcome.lastReason)
                is WaitOutcome.CaptureFailed ->
                    notObserved(took, outcome.lastReason) + " The last capture failed: ${outcome.message}"
                is WaitOutcome.Cancelled ->
                    "CANCELLED while checking for $expected, after $took. Whether it happened was not verified."
            }
            val notes = WaitReport.firstCaptureNote(outcome, expectation.timeoutMs.toLong()) +
                WaitReport.refusedNote(outcome.refused())
            val text = before.summary() + "\n" + dispatched + "\n" + verdict + notes +
                (outcome.seen()?.let { "\nAfter $action: " + it.preface() } ?: "")
            return ToolResult(listOf(ToolContent.Text(text)), isError = kind.isError)
        }

        private fun satisfied(
            kind: ActionOutcome,
            outcome: WaitOutcome.Satisfied,
            took: String,
            heldBefore: UiCondition.Check,
        ): String = when (kind) {
            ActionOutcome.INCONCLUSIVE ->
                "INCONCLUSIVE: ${condition.selector.describe()} was already ${condition.expected} before " +
                    "$action, so seeing it after ($took) proves nothing about $action. Before: ${heldBefore.reason}. " +
                    "Expect something $action changes, such as a status that appears."
            else ->
                "VERIFIED: ${condition.selector.describe()} is ${condition.expected} after $took. " +
                    "${outcome.reason}. Before $action it was not: ${heldBefore.reason}."
        }

        private fun notObserved(took: String, lastReason: String): String =
            "NOT OBSERVED: waited ${WaitReport.seconds(expectation.timeoutMs.toLong())} s after $action for " +
                "$expected and did not see it; $took. Last: $lastReason. The input may still have landed, or the " +
                "screen may need longer: check with android_get_ui_tree or android_wait_for_element before acting " +
                "again."
    }

    private fun WaitOutcome.refused(): Int = when (this) {
        is WaitOutcome.Satisfied -> refusedCaptures
        is WaitOutcome.TimedOut -> refusedCaptures
        is WaitOutcome.CaptureFailed -> refusedCaptures
        is WaitOutcome.Cancelled -> 0
    }

    /** The last screen the wait saw, if any capture worked. */
    private fun WaitOutcome.seen(): UiObservation? = when (this) {
        is WaitOutcome.Satisfied -> observation
        is WaitOutcome.TimedOut -> lastObservation
        is WaitOutcome.CaptureFailed -> lastObservation
        is WaitOutcome.Cancelled -> null
    }

    private const val TEST_TAG = "expectTestTag"
    private const val TEXT = "expectText"
    private const val DESCRIPTION = "expectContentDescription"
    private const val EXACT = "expectExact"
    private const val EXACT_TAG = "expectExactTag"
    private const val UNTIL = "expectUntil"
    private const val TIMEOUT = "expectTimeoutMs"
    private const val DEFAULT_UNTIL = "visible"
    private const val DEFAULT_TIMEOUT_MS = 5_000
    private const val MAX_TIMEOUT_MS = 60_000
    private const val POLL_INTERVAL_MS = 500L
}

/**
 * Observes the screen and resolves the one element an action lands on.
 *
 * A refusal starts with the observation's summary too, so "no match" or "ambiguous" says which
 * window on which device it was decided against.
 */
private fun resolveElement(
    device: ConnectedDevice,
    arguments: JsonObject,
    action: UiTreeSearch.Action,
    signal: CancellationSignal,
): Resolved {
    val selector = arguments.toSelector()
    require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }

    val observation = UiTreeReader.read(device, cancellation = signal)
    val tree = observation.tree
    // Ambiguity is decided over the whole tree: an off-screen duplicate still makes a selector ambiguous.
    val match = observation.refusing { UiTreeSearch.findUnique(tree, selector, action) }
        ?: throw IllegalStateException(
            observation.summary() + "\nNo element matched ${selector.describe()}. " + tree.frameworkNote() +
                " Call android_get_ui_tree to see what is actually on screen. An element scrolled out of " +
                "view is often left out of the capture entirely; android_scroll_to_element brings it in.",
        )

    // Compose usually puts text on a child and the click handler on its parent, so the node
    // carrying the text is often not the one that can be tapped.
    val target = observation.refusing { UiTreeSearch.actionTarget(tree, match, action, selector) }
    val visibility = ViewportVisibility.of(observation, target)
    observation.refusing {
        require(visibility.presence != Presence.OUTSIDE_VIEWPORT && visibility.presence != Presence.ZERO_AREA) {
            "'${target.label}' at ${target.bounds} is ${visibility.describe()}. Nothing was dispatched: " +
                "call android_scroll_to_element with the same selector first."
        }
    }
    return Resolved(observation, target, visibility)
}

private class Resolved(val observation: UiObservation, val target: UiNode, val visibility: NodeVisibility) {

    /**
     * The centre of the part in view, so a partly scrolled-out control is pressed where it can be
     * seen rather than at a centre that may lie under its container's edge. Without a viewport it
     * is the centre of the bounds, and [where] says the viewport was unknown.
     */
    val x: Int get() = (visibility.visibleRegion ?: target.bounds).centerX
    val y: Int get() = (visibility.visibleRegion ?: target.bounds).centerY

    val where: String
        get() = when (visibility.visibleRegion) {
            null ->
                "at ($x,$y), the centre of its bounds ${target.bounds}; viewport unknown, so whether it " +
                    "is on screen was not checked"
            target.bounds -> "at ($x,$y), ${visibility.describe()}"
            else ->
                "at ($x,$y), the centre of its part in the viewport ${visibility.visibleRegion} of " +
                    "${target.bounds}; ${visibility.describe()}"
        }
}

/** Runs a selection step, prefixing a refusal (ambiguous, disabled, out of scope) with [this] summary. */
private inline fun <T> UiObservation.refusing(select: () -> T): T = try {
    select()
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException(summary() + "\n" + e.message, e)
}
