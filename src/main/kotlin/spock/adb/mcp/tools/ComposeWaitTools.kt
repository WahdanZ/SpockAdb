package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.mcp.tools.UiTreeReader.elementSelector
import spock.adb.mcp.tools.UiTreeReader.preface
import spock.adb.mcp.tools.UiTreeReader.toSelector
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.StateProperty
import spock.adb.uitree.UiCaptureException
import spock.adb.uitree.UiCondition
import spock.adb.uitree.UiSelector
import spock.adb.uitree.UiWaiter
import spock.adb.uitree.WaitOutcome
import java.util.Locale

/**
 * `android_wait_for_element` — capture until an element appears, goes, or reaches a state.
 *
 * Without it an agent waits by calling an assertion again and again, paying a round trip and a
 * tree's worth of tokens for each look, or by sleeping a guessed number of seconds. The polling
 * is [UiWaiter]'s; this is the part about talking to an agent.
 */
class WaitForElementTool : AdbTool {
    override val name = "android_wait_for_element"
    override val description =
        "Wait, up to a limit, until an element is visible (in the viewport), present (in the tree), gone " +
            "(not in the tree), hidden (not in the viewport), or in a state: enabled, disabled, checked, " +
            "unchecked, selected, unselected, focused. States need exactly one match. Reads the screen " +
            "every pollIntervalMs and changes nothing on the device. 'gone' is met at once by an element " +
            "that was never there, so wait for it to appear first when that matters. On a real device an " +
            "element scrolled out of a Compose list usually drops out of the tree, so 'gone' and 'hidden' " +
            "cannot tell removed from scrolled away."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        elementSelector()
        enumeration("until", "What to wait for. Defaults to visible.", UntilArgument.WORDS)
        integer(
            "timeoutMs",
            "How long to wait, in milliseconds, 0 to $MAX_TIMEOUT_MS. Defaults to $DEFAULT_TIMEOUT_MS. Over " +
                "HTTP, which cannot cancel a call, at most $UNCANCELLABLE_MAX_TIMEOUT_MS. The first look at the " +
                "screen always completes, even past this limit, so 0 looks exactly once.",
        )
        integer(
            "pollIntervalMs",
            "Pause between captures, in milliseconds, $MIN_POLL_MS to $MAX_POLL_MS. Defaults to $DEFAULT_POLL_MS.",
        )
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // Taken first, on this thread: the default signal is this thread's interrupt.
        val signal = context.cancellationSignal()
        val selector = arguments.toSelector()
        require(!selector.isEmpty) { "Give at least one of testTag, text or contentDescription." }
        val until = arguments.optionalString("until") ?: "visible"
        val condition = UntilArgument.parse(until, selector)
            ?: throw IllegalArgumentException(
                "Unknown until '$until'. Use one of: ${UntilArgument.WORDS.joinToString()}.",
            )
        val requestedMs = arguments.optionalInt("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(0, MAX_TIMEOUT_MS)
        val timeoutMs = timeoutFor(requestedMs, context.canCancel)
        val pollMs = arguments.optionalInt("pollIntervalMs", DEFAULT_POLL_MS).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))

        // Measured by the first capture that works and reused: the display does not change while we wait.
        var metrics: DisplayMetrics? = null
        val waiter = UiWaiter(
            capture = { seconds ->
                UiTreeReader.read(device, metrics, seconds, signal).also { metrics = it.metrics }
            },
            signal = signal,
        )
        val outcome = waiter.await(condition, timeoutMs.toLong(), pollMs.toLong())
        val result = WaitReport.report(outcome, condition, device.serialNumber, timeoutMs.toLong())
        return if (timeoutMs < requestedMs) result.withNote(cappedNote(requestedMs)) else result
    }

    private fun cappedNote(requestedMs: Int): String =
        " timeoutMs was capped at $UNCANCELLABLE_MAX_TIMEOUT_MS from $requestedMs: this transport cannot cancel " +
            "a call, so a wait here is kept short. Wait again to wait longer, or use the stdio transport."

    private fun ToolResult.withNote(note: String): ToolResult =
        copy(content = content.map { if (it is ToolContent.Text) ToolContent.Text(it.text + note) else it })

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000
        private const val MAX_TIMEOUT_MS = 60_000

        /**
         * The longest wait over a transport that cannot cancel it — HTTP. An abandoned wait holds
         * one of the HTTP server's four threads until it ends, so four abandoned 60-second waits
         * would stall every HTTP call, `tools/list` included, for a minute.
         */
        const val UNCANCELLABLE_MAX_TIMEOUT_MS = 15_000

        /** [requestedMs], capped at [UNCANCELLABLE_MAX_TIMEOUT_MS] when nothing can cancel the wait. */
        fun timeoutFor(requestedMs: Int, canCancel: Boolean): Int =
            if (canCancel) requestedMs else minOf(requestedMs, UNCANCELLABLE_MAX_TIMEOUT_MS)

        private const val DEFAULT_POLL_MS = 500
        private const val MIN_POLL_MS = 100
        private const val MAX_POLL_MS = 5_000
    }
}

/**
 * How a [WaitOutcome] reads to an agent. Shared by `android_wait_for_element` and an element
 * action's expected result, so both describe a wait the same way.
 */
internal object WaitReport {

    /** [timeoutMs] is the limit the wait was given, which the first capture may have run past. */
    fun report(outcome: WaitOutcome, condition: UiCondition, serial: String, timeoutMs: Long): ToolResult {
        val what = "${condition.selector.describe()} to be ${condition.expected}"
        val took = took(outcome)
        val overran = firstCaptureNote(outcome, timeoutMs)
        return when (outcome) {
            is WaitOutcome.Satisfied -> ToolResult.text(
                outcome.observation.preface() +
                    "\nPASS: ${condition.selector.describe()} is ${condition.expected} after $took. " +
                    outcome.reason + "." + overran +
                    refusedNote(outcome.refusedCaptures),
            )
            is WaitOutcome.TimedOut -> ToolResult.error(
                (outcome.lastObservation?.preface()?.plus("\n") ?: "No capture worked.\n") +
                    "FAIL: timed out waiting for $what; $took. Last: ${outcome.lastReason}." + overran +
                    refusedNote(outcome.refusedCaptures),
            )
            is WaitOutcome.Cancelled -> ToolResult.error(
                "CANCELLED waiting for $what, after $took; nothing was changed on the device.",
            )
            is WaitOutcome.CaptureFailed -> ToolResult.error(
                when (outcome.kind) {
                    UiCaptureException.Kind.DEVICE_UNAVAILABLE ->
                        "FAIL: device $serial became unavailable while waiting for $what, after $took. " +
                            outcome.message
                    else ->
                        (outcome.lastObservation?.preface()?.plus("\n") ?: "") +
                            "FAIL: timed out waiting for $what; $took, ${outOfTime(outcome)} " +
                            "${outcome.message} Before it: ${outcome.lastReason}." + overran +
                            refusedNote(outcome.refusedCaptures)
                },
            )
        }
    }

    fun took(outcome: WaitOutcome): String = "${outcome.captures} observation(s), ${seconds(outcome.elapsedMs)} s"

    /**
     * Said when the first capture, which always runs to completion, took longer than the whole
     * wait was given: a verdict reached after its limit should say so, not look like a slow device.
     *
     * Not said for a limit of zero, which asks for exactly that one look, nor for an overrun too
     * small to show in tenths of a second, which would read "took 1.0 s, past the 1.0 s limit".
     */
    fun firstCaptureNote(outcome: WaitOutcome, timeoutMs: Long): String {
        val first = outcome.firstCaptureMs ?: return ""
        if (timeoutMs == 0L || seconds(first) == seconds(timeoutMs) || first <= timeoutMs) return ""
        return " The first observation took ${seconds(first)} s, past the ${seconds(timeoutMs)} s limit: a wait " +
            "always completes its first observation."
    }

    fun refusedNote(refused: Int): String =
        if (refused == 0) {
            ""
        } else {
            " uiautomator refused or left empty $refused capture(s), usually a UI still animating."
        }

    /**
     * A later capture is given only what is left of the wait, so one out of time is the wait
     * running out too. The first had a capture's full time, so it is a dump that never finished.
     */
    private fun outOfTime(outcome: WaitOutcome.CaptureFailed): String =
        if (outcome.captures == 1) {
            "which did not finish even in a capture's full time; the device may be badly loaded."
        } else {
            "the last of which did not finish in the time left. A dump takes seconds on many devices, and " +
                "longer while the UI animates, so allow a timeoutMs several dumps long."
        }

    fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f", millis / MILLIS_PER_SECOND)

    private const val MILLIS_PER_SECOND = 1_000.0
}

/**
 * The words `until` accepts, each one [UiCondition]. Kept apart from the tool so that anything
 * else stating an expected UI result reads it the same way.
 */
internal object UntilArgument {

    private val STATES = mapOf(
        "enabled" to (StateProperty.ENABLED to true),
        "disabled" to (StateProperty.ENABLED to false),
        "checked" to (StateProperty.CHECKED to true),
        "unchecked" to (StateProperty.CHECKED to false),
        "selected" to (StateProperty.SELECTED to true),
        "unselected" to (StateProperty.SELECTED to false),
        "focused" to (StateProperty.FOCUSED to true),
    )

    val WORDS: List<String> = listOf("visible", "present", "gone", "hidden") + STATES.keys

    /** Null for a word not in [WORDS]. */
    fun parse(until: String, selector: UiSelector): UiCondition? = when (val word = until.lowercase(Locale.ROOT)) {
        "visible" -> UiCondition.Visible(selector)
        "present" -> UiCondition.Present(selector)
        "gone" -> UiCondition.Gone(selector)
        "hidden" -> UiCondition.Hidden(selector)
        else -> STATES[word]?.let { (state, value) -> UiCondition.HasState(selector, state, value) }
    }
}
