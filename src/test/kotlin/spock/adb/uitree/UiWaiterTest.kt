package spock.adb.uitree

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.CancellationSignal
import spock.adb.uitree.UiCaptureException.Kind

/**
 * A wait is only as good as its bounds: it must stop at its limit, stop when asked, and say how
 * much it looked. Time here is a fake clock that the fake capture and sleeper move forward.
 */
class UiWaiterTest {

    private var nowNanos = 0L
    private val sleeps = mutableListOf<Long>()
    private val captureTimeouts = mutableListOf<Long>()

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    /** A waiter whose capture answers from [script] in turn, repeating the last, and takes [captureMs]. */
    private fun waiter(
        script: List<() -> UiObservation>,
        captureMs: Long = 0,
        signal: CancellationSignal = CancellationSignal { false },
        sleep: (Long) -> Unit = {
            sleeps += it
            nowNanos += it * NANOS
        },
    ): UiWaiter {
        var next = 0
        return UiWaiter(
            capture = { seconds ->
                captureTimeouts += seconds
                nowNanos += captureMs * NANOS
                script[minOf(next++, script.lastIndex)]()
            },
            signal = signal,
            clockNanos = { nowNanos },
            sleep = sleep,
        )
    }

    @Test
    fun `met on the first capture, it never pauses`() {
        val outcome = waiter(listOf { screen(button("save")) })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        val satisfied = assertInstanceOf(WaitOutcome.Satisfied::class.java, outcome)
        assertEquals(1, satisfied.captures)
        assertTrue(sleeps.isEmpty(), sleeps.toString())
        assertTrue(satisfied.reason.contains("within the viewport"), satisfied.reason)
    }

    @Test
    fun `met on the third capture, after two pauses of the poll interval`() {
        val outcome = waiter(listOf({ screen() }, { screen() }, { screen(button("save")) }))
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        val satisfied = assertInstanceOf(WaitOutcome.Satisfied::class.java, outcome)
        assertEquals(3, satisfied.captures)
        assertEquals(1_000L, sleeps.sum())
        assertEquals(1_000L, satisfied.elapsedMs)
    }

    @Test
    fun `a timeout returns the last observation and never pauses past the deadline`() {
        val last = screen()
        val pauseEnds = mutableListOf<Long>()
        val outcome = waiter(
            listOf({ screen() }, { screen() }, { last }),
            captureMs = 300,
            sleep = {
                nowNanos += it * NANOS
                pauseEnds += nowNanos / NANOS
            },
        ).await(UiCondition.Visible(tag("save")), timeoutMs = 2_000, pollIntervalMs = 700)

        val timedOut = assertInstanceOf(WaitOutcome.TimedOut::class.java, outcome)
        assertSame(last, timedOut.lastObservation)
        assertTrue(timedOut.lastReason.contains("nothing matched"), timedOut.lastReason)
        // Captures end at 300, 1300 and 2300 ms; the pauses between them end at 1000 and 2000.
        assertEquals(3, timedOut.captures)
        assertTrue(pauseEnds.all { it <= 2_000 }, pauseEnds.toString())
        assertEquals(2_000L, pauseEnds.last())
        // Only the last capture, already under way, may cross the deadline.
        assertEquals(2_300L, timedOut.elapsedMs)
    }

    @Test
    fun `each capture is given what is left, rounded up to whole seconds, at least one`() {
        waiter(listOf { screen() })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 2_500, pollIntervalMs = 1_000)

        // 2.5 s left, then 1.5, then 0.5, then nothing: the last look still gets a second.
        assertEquals(listOf(3L, 2L, 1L, 1L), captureTimeouts)
    }

    @Test
    fun `a long wait caps each capture at the dump's own limit`() {
        waiter(listOf { screen(button("save")) })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 60_000, pollIntervalMs = 500)

        assertEquals(listOf(UiWaiter.MAX_CAPTURE_SECONDS), captureTimeouts)
    }

    @Test
    fun `a zero timeout is exactly one capture`() {
        val outcome = waiter(listOf { screen() })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 0, pollIntervalMs = 500)

        assertInstanceOf(WaitOutcome.TimedOut::class.java, outcome)
        assertEquals(1, outcome.captures)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a signal set before the start takes no capture`() {
        val outcome = waiter(listOf { screen() }, signal = { true })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        assertInstanceOf(WaitOutcome.Cancelled::class.java, outcome)
        assertEquals(0, outcome.captures)
    }

    @Test
    fun `a signal set during a pause ends the wait without another capture`() {
        var cancelled = false
        val outcome = waiter(
            listOf { screen() },
            signal = { cancelled },
            sleep = {
                sleeps += it
                nowNanos += it * NANOS
                cancelled = true
            },
        ).await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 2_000)

        assertInstanceOf(WaitOutcome.Cancelled::class.java, outcome)
        assertEquals(1, outcome.captures)
        assertTrue(sleeps.sum() < 2_000, "a pause is sliced so a flag is seen before it ends: $sleeps")
    }

    @Test
    fun `an interrupted pause is a cancel, and the interrupt is kept for the thread's owner`() {
        val outcome = waiter(listOf { screen() }, sleep = { throw InterruptedException() })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        assertInstanceOf(WaitOutcome.Cancelled::class.java, outcome)
        assertTrue(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `a cancelled capture is a cancel`() {
        val outcome = waiter(listOf { throw UiCaptureException(Kind.CANCELLED, "UI capture cancelled") })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        assertInstanceOf(WaitOutcome.Cancelled::class.java, outcome)
        assertEquals(1, outcome.captures)
    }

    @Test
    fun `a lost device stops the wait after one capture`() {
        val outcome = waiter(listOf { throw UiCaptureException(Kind.DEVICE_UNAVAILABLE, "Device x is gone.") })
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        val failed = assertInstanceOf(WaitOutcome.CaptureFailed::class.java, outcome)
        assertEquals(Kind.DEVICE_UNAVAILABLE, failed.kind)
        assertEquals(1, failed.captures)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a capture out of time stops the wait, since it already had what was left`() {
        val outcome = waiter(listOf({ screen() }, { throw UiCaptureException(Kind.TIMED_OUT, "too slow") }))
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        val failed = assertInstanceOf(WaitOutcome.CaptureFailed::class.java, outcome)
        assertEquals(Kind.TIMED_OUT, failed.kind)
        assertEquals(2, failed.captures)
        assertFalse(failed.lastObservation == null, "the capture that worked is kept")
        assertTrue(failed.lastReason.contains("nothing matched"), failed.lastReason)
    }

    @Test
    fun `refused dumps are counted and retried until one works`() {
        val refused = { throw UiCaptureException(Kind.DUMP_REFUSED, "could not get idle state") }
        val outcome = waiter(listOf(refused, refused, { screen(button("save")) }))
            .await(UiCondition.Visible(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        val satisfied = assertInstanceOf(WaitOutcome.Satisfied::class.java, outcome)
        assertEquals(3, satisfied.captures)
        assertEquals(2, satisfied.refusedCaptures)
    }

    @Test
    fun `a state wait on an ambiguous selector times out saying it was ambiguous`() {
        val outcome = waiter(listOf { screen(button("save", enabled = false) + button("save_as", enabled = false)) })
            .await(enabled("save"), timeoutMs = 1_000, pollIntervalMs = 500)

        val timedOut = assertInstanceOf(WaitOutcome.TimedOut::class.java, outcome)
        assertTrue(timedOut.lastReason.contains("Ambiguous"), timedOut.lastReason)
    }

    @Test
    fun `gone is met on the first capture by an element that was never there`() {
        val outcome = waiter(listOf { screen() })
            .await(UiCondition.Gone(tag("save")), timeoutMs = 5_000, pollIntervalMs = 500)

        assertInstanceOf(WaitOutcome.Satisfied::class.java, outcome)
        assertEquals(1, outcome.captures)
    }

    @Test
    fun `a state wait passes once the one match reaches it`() {
        val outcome = waiter(listOf({ screen(button("save", enabled = false)) }, { screen(button("save")) }))
            .await(enabled("save"), timeoutMs = 5_000, pollIntervalMs = 500)

        val satisfied = assertInstanceOf(WaitOutcome.Satisfied::class.java, outcome)
        assertEquals(2, satisfied.captures)
        assertTrue(satisfied.reason.endsWith("is enabled"), satisfied.reason)
    }

    @Test
    fun `unchecked is never met by a control that cannot be checked`() {
        val check = UiCondition.HasState(tag("save"), StateProperty.CHECKED, false).evaluate(screen(button("save")))

        assertFalse(check.satisfied)
        assertTrue(check.reason.contains("not checkable"), check.reason)
    }

    @Test
    fun `hidden is met by a match outside the viewport, and not by one in it`() {
        val below = """<node class="android.widget.Button" resource-id="save" package="p" enabled="true"
            bounds="[0,3000][100,3100]" />"""

        assertTrue(UiCondition.Hidden(tag("save")).evaluate(screen(below)).satisfied)
        assertFalse(UiCondition.Hidden(tag("save")).evaluate(screen(button("save"))).satisfied)
        assertFalse(UiCondition.Visible(tag("save")).evaluate(screen(below)).satisfied)
        assertTrue(UiCondition.Present(tag("save")).evaluate(screen(below)).satisfied)
    }

    @Test
    fun `without a viewport, visible and hidden are never met by a match`() {
        // No display size, and a window with no area: nothing to call a viewport.
        val unknown = screen().copy(
            tree = UiTreeParser.parse(
                """<hierarchy><node class="v" bounds="[0,0][0,0]">${button("save")}</node></hierarchy>""",
            ),
            metrics = DisplayMetrics.UNKNOWN,
        )

        assertFalse(UiCondition.Visible(tag("save")).evaluate(unknown).satisfied)
        assertFalse(UiCondition.Hidden(tag("save")).evaluate(unknown).satisfied)
        assertTrue(UiCondition.Visible(tag("save")).evaluate(unknown).reason.contains("viewport is unknown"))
    }

    private fun tag(value: String) = UiSelector(testTag = value)

    private fun enabled(tag: String) = UiCondition.HasState(tag(tag), StateProperty.ENABLED, true)

    private fun button(tag: String, enabled: Boolean = true) =
        """<node class="android.widget.Button" resource-id="$tag" package="p" clickable="true" enabled="$enabled"
            bounds="[0,100][300,200]" />"""

    /** A 1080x2400 display showing [children] in one window. */
    private fun screen(children: String = "") = UiObservation(
        tree = UiTreeParser.parse(
            """<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="p"
                bounds="[0,0][1080,2400]">$children</node></hierarchy>""",
        ),
        deviceSerial = "emulator-5554",
        startedAtMillis = 0,
        completedAtMillis = 0,
        metrics = DisplayMetrics(densityDpi = 420, naturalWidthPx = 1080, naturalHeightPx = 2400),
    )

    private companion object {
        const val NANOS = 1_000_000L
    }
}
