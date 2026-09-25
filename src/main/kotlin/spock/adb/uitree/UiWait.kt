package spock.adb.uitree

import spock.adb.CancellationSignal
import javax.swing.SwingUtilities

/**
 * What a wait waits for, decided against one capture at a time.
 *
 * Matching is [UiTreeSearch.findAll]'s, so a node with no area never matches and so never
 * counts as present. A selector that cannot be resolved — an ambiguous or missing
 * `containerTag`, or several matches where one is needed — is never satisfied, and the refusal
 * is kept as the reason, which a timed-out wait reports.
 */
sealed class UiCondition {

    abstract val selector: UiSelector

    /** The `until` word it answers to, for a sentence: "testTag='x' is visible". */
    abstract val expected: String

    fun evaluate(observation: UiObservation): Check = try {
        decide(observation)
    } catch (e: IllegalArgumentException) {
        Check(satisfied = false, reason = e.message ?: "${selector.describe()} could not be resolved")
    }

    protected abstract fun decide(observation: UiObservation): Check

    /** Whether the condition held in one capture, and in words what was seen. */
    data class Check(val satisfied: Boolean, val reason: String)

    /** A match with something in the viewport: in it, or partly in it. Never met without a viewport. */
    data class Visible(override val selector: UiSelector) : UiCondition() {
        override val expected = "visible"

        override fun decide(observation: UiObservation): Check {
            val matches = UiTreeSearch.findAll(observation.tree, selector)
            if (matches.isEmpty()) return nothingMatched(selector)
            if (observation.viewport == null) return viewportUnknown(matches)
            val visibility = ViewportVisibility.classifyAll(observation)
            val first = matches.firstOrNull { visibility.getValue(it).inViewport }
                ?: return Check(
                    false,
                    "${matches.size} match(es) for ${selector.describe()}, all outside the viewport or their " +
                        "scroll container",
                )
            return Check(true, "'${first.label}' at ${first.bounds} is ${visibility.getValue(first).describe()}")
        }
    }

    /** A match in the tree, wherever it is. */
    data class Present(override val selector: UiSelector) : UiCondition() {
        override val expected = "present"

        override fun decide(observation: UiObservation): Check {
            val first = UiTreeSearch.findAll(observation.tree, selector).firstOrNull()
                ?: return nothingMatched(selector)
            return Check(true, "'${first.label}' is in the tree at ${first.bounds}")
        }
    }

    /** No match in the tree. Met at once by an element that was never there. */
    data class Gone(override val selector: UiSelector) : UiCondition() {
        override val expected = "gone"

        override fun decide(observation: UiObservation): Check {
            val matches = UiTreeSearch.findAll(observation.tree, selector)
            val first = matches.firstOrNull()
                ?: return Check(true, "nothing in the tree matches ${selector.describe()}")
            return Check(
                false,
                "${matches.size} match(es) still in the tree, the first '${first.label}' at ${first.bounds}",
            )
        }
    }

    /** No match with anything in the viewport. Not met while a match exists and the viewport is unknown. */
    data class Hidden(override val selector: UiSelector) : UiCondition() {
        override val expected = "hidden"

        override fun decide(observation: UiObservation): Check {
            val matches = UiTreeSearch.findAll(observation.tree, selector)
            if (matches.isEmpty()) return Check(true, "nothing in the tree matches ${selector.describe()}")
            if (observation.viewport == null) return viewportUnknown(matches)
            val visibility = ViewportVisibility.classifyAll(observation)
            val inView = matches.filter { visibility.getValue(it).inViewport }
            val first = inView.firstOrNull()
                ?: return Check(true, "${matches.size} match(es) in the tree, none in the viewport")
            return Check(
                false,
                "${inView.size} of ${matches.size} match(es) still in the viewport, the first '${first.label}' at " +
                    "${first.bounds}",
            )
        }
    }

    /** Exactly one match, with [state] equal to [value]. */
    data class HasState(
        override val selector: UiSelector,
        val state: StateProperty,
        val value: Boolean,
    ) : UiCondition() {
        override val expected: String get() = state.word(value)

        override fun decide(observation: UiObservation): Check {
            val node = UiTreeSearch.findUnique(observation.tree, selector) ?: return nothingMatched(selector)
            // A control that cannot be checked reads as unchecked, which would pass a wait for "unchecked".
            if (state == StateProperty.CHECKED && !node.checkable) {
                return Check(false, "'${node.label}' is not checkable, so it has no checked state")
            }
            val actual = state.read(node)
            return Check(actual == value, "'${node.label}' at ${node.bounds} is ${state.word(actual)}")
        }
    }

    protected fun nothingMatched(selector: UiSelector) = Check(false, "nothing matched ${selector.describe()}")

    protected fun viewportUnknown(matches: List<UiNode>) = Check(
        false,
        "${matches.size} match(es) in the tree, but the viewport is unknown, so whether any is on screen was " +
            "not checked",
    )
}

/** A node state a wait can watch. */
enum class StateProperty(private val on: String, private val off: String, val read: (UiNode) -> Boolean) {
    ENABLED("enabled", "disabled", UiNode::enabled),
    CHECKED("checked", "unchecked", UiNode::checked),
    SELECTED("selected", "unselected", UiNode::selected),
    FOCUSED("focused", "not focused", UiNode::focused),
    ;

    fun word(value: Boolean): String = if (value) on else off
}

/** How a wait ended. Every outcome carries how many captures it took, which is what a wait costs. */
sealed class WaitOutcome {

    abstract val elapsedMs: Long
    abstract val captures: Int

    data class Satisfied(
        val observation: UiObservation,
        /** What was seen that met the condition. */
        val reason: String,
        override val elapsedMs: Long,
        override val captures: Int,
        /** Captures `uiautomator` refused or left empty before this one worked. */
        val refusedCaptures: Int = 0,
    ) : WaitOutcome()

    data class TimedOut(
        /** Null when no capture worked at all. */
        val lastObservation: UiObservation?,
        val lastReason: String,
        override val elapsedMs: Long,
        override val captures: Int,
        val refusedCaptures: Int,
    ) : WaitOutcome()

    /** The caller asked to stop. Nothing is wrong with the device. */
    data class Cancelled(override val elapsedMs: Long, override val captures: Int) : WaitOutcome()

    /**
     * A capture failed in a way another capture would not fix: a lost device, or one out of time.
     * A capture is only ever given what is left of the wait, so one out of time also means the
     * wait is; [lastReason] is what the last capture that worked showed.
     */
    data class CaptureFailed(
        val kind: UiCaptureException.Kind,
        val message: String,
        val lastObservation: UiObservation?,
        override val captures: Int,
        override val elapsedMs: Long,
        val lastReason: String,
        val refusedCaptures: Int = 0,
    ) : WaitOutcome()
}

/**
 * Captures the screen until a [UiCondition] holds, the time runs out, or the caller cancels.
 *
 * Each capture is given what is left of the wait, rounded up to whole seconds (at least one,
 * at most [MAX_CAPTURE_SECONDS]), so a slow dump cannot run far past the limit. Rounding up
 * means the wait may overrun its limit by under a second, plus the time to read back a dump
 * that finished just before its own limit. A limit of zero is exactly one capture.
 *
 * Cancellation is polled, never forced: [signal] is checked before each capture, handed to
 * the capture itself, and checked between slices of each pause. An interrupt during a pause
 * ends the wait as cancelled, with the interrupt flag set again for the thread's owner.
 *
 * A refused or empty dump — a UI still animating — is counted and retried. A lost device
 * ends the wait, and so does a capture out of time: it already had all that was left.
 *
 * Blocks for up to the limit, so it must never run on the EDT.
 */
class UiWaiter(
    private val capture: (timeoutSeconds: Long) -> UiObservation,
    private val signal: CancellationSignal,
    private val clockNanos: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    fun await(condition: UiCondition, timeoutMs: Long, pollIntervalMs: Long): WaitOutcome {
        check(!SwingUtilities.isEventDispatchThread()) {
            "A UI wait blocks for up to its timeout; never run it on the EDT."
        }
        require(timeoutMs >= 0) { "timeoutMs must not be negative." }
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive." }

        val run = Run(clockNanos(), timeoutMs)
        while (!signal.isCancelled()) {
            run.attempt(condition)?.let { return it }
            if (run.remainingMs() <= 0) return run.timedOut()
            if (!pause(minOf(pollIntervalMs, run.remainingMs()))) return run.cancelled()
        }
        return run.cancelled()
    }

    /** False when cancelled during the pause. Sliced so a flag set mid-pause is seen promptly. */
    private fun pause(millis: Long): Boolean {
        var left = millis
        while (left > 0) {
            if (signal.isCancelled()) return false
            val slice = minOf(left, PAUSE_SLICE_MS)
            try {
                sleep(slice)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            left -= slice
        }
        return !signal.isCancelled()
    }

    /** One wait's running totals. */
    private inner class Run(private val startNanos: Long, timeoutMs: Long) {
        private val deadlineNanos = startNanos + timeoutMs * NANOS_PER_MILLI
        private var captures = 0
        private var refused = 0
        private var last: UiObservation? = null
        private var lastReason = "no capture completed"

        fun elapsedMs(): Long = (clockNanos() - startNanos) / NANOS_PER_MILLI

        fun remainingMs(): Long = (deadlineNanos - clockNanos()) / NANOS_PER_MILLI

        /** The outcome when this capture ends the wait; null to go on. */
        fun attempt(condition: UiCondition): WaitOutcome? {
            captures++
            val observation = try {
                capture(captureSeconds(remainingMs()))
            } catch (e: UiCaptureException) {
                return failed(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return cancelled()
            }
            last = observation
            val check = condition.evaluate(observation)
            if (check.satisfied) return WaitOutcome.Satisfied(observation, check.reason, elapsedMs(), captures, refused)
            lastReason = check.reason
            return null
        }

        private fun failed(e: UiCaptureException): WaitOutcome? = when {
            e.kind == UiCaptureException.Kind.CANCELLED -> cancelled()
            e.kind.retryable -> {
                refused++
                lastReason = e.message ?: "the capture was refused"
                null
            }
            else -> WaitOutcome.CaptureFailed(
                e.kind,
                e.message.orEmpty(),
                last,
                captures,
                elapsedMs(),
                lastReason,
                refused,
            )
        }

        fun timedOut() = WaitOutcome.TimedOut(last, lastReason, elapsedMs(), captures, refused)

        fun cancelled() = WaitOutcome.Cancelled(elapsedMs(), captures)
    }

    companion object {
        /** A capture's own limit: what [UiTreeOperations][spock.adb.device.ops.UiTreeOperations] allows a dump. */
        const val MAX_CAPTURE_SECONDS = 30L
        private const val PAUSE_SLICE_MS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /** What is left, rounded up to whole seconds, from 1 to [MAX_CAPTURE_SECONDS]. */
        fun captureSeconds(remainingMs: Long): Long =
            ((remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceIn(1, MAX_CAPTURE_SECONDS)
    }
}
