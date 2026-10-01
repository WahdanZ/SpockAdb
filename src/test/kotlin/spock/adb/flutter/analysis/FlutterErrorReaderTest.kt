package spock.adb.flutter.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.DiagnosticShell
import spock.adb.diagnostics.LikelyProblem.Severity
import java.time.ZoneOffset

/**
 * Against the five `Flutter.Error` events the sample app posted: a layout overflow, a tap
 * handler's StateError, a build() ArgumentError, then the overflow and the tap error again.
 */
class FlutterErrorReaderTest {

    private val events = FlutterFixtures.events(FlutterExtensionEvent.ERROR)

    @Test
    fun `each error reads as its headline, where it came from and the widget that caused it`() {
        val errors = FlutterErrorReader.read(events)

        assertEquals(5, errors.size)
        assertEquals(
            "Exception caught by rendering library: A RenderFlex overflowed by 219 pixels on the right." +
                " — Row at lib/fixtures/layout.dart:30:17",
            errors[0].summary,
        )
        assertEquals("assertion", errors[0].thrown)
        assertEquals(0, errors[0].errorsSinceReload)

        assertEquals("Exception caught by gesture: Bad state: Sample error thrown in a tap handler", errors[1].summary)
        assertEquals("StateError", errors[1].thrown)
        assertNull(errors[1].widget)

        assertEquals(
            "Exception caught by widgets library: Invalid argument(s): Sample error thrown in build()" +
                " — _ThrowsInBuild at lib/fixtures/errors.dart:105:33",
            errors[2].summary,
        )
        assertEquals("ArgumentError", errors[2].thrown)
        assertEquals(listOf(0, 1, 2, 3, 4), errors.map { it.errorsSinceReload })
    }

    @Test
    fun `repeats are one problem with a count, and every problem is an error`() {
        val result = FlutterErrorReader.summarise(events, ZoneOffset.UTC)

        assertEquals(listOf(2, 2, 1), result.problems.map { it.count })
        assertTrue(result.problems.all { it.type == "flutterError" && it.severity == Severity.ERROR })
        assertTrue(result.problems.all { it.section == FLUTTER_SECTION })
        assertTrue(result.problems[0].summary.startsWith("Exception caught by rendering library: A RenderFlex"))
        // The overflow's second occurrence, at 20:57:00 UTC.
        assertEquals("10-01 20:57:00.680", result.problems[0].lastSeen)
        assertEquals(5, result.liveCount)
        assertEquals(0, result.historyCount)
        assertEquals(4, result.errorsSinceReload)
    }

    @Test
    fun `errors replayed from before the connection are counted apart and say so`() {
        val history = FlutterFixtures.events(FlutterExtensionEvent.ERROR, history = true).take(2)
        val live = events.drop(3)
        val result = FlutterErrorReader.summarise(history + live, ZoneOffset.UTC)

        assertEquals(2, result.historyCount)
        assertEquals(2, result.liveCount)
        val overflow = result.problems.first { it.summary.contains("RenderFlex") }
        assertEquals(2, overflow.count)
        assertTrue(overflow.summary.endsWith(" (1 before Spock connected)"), overflow.summary)

        val onlyHistory = FlutterErrorReader.summarise(history, ZoneOffset.UTC).problems
        assertTrue(onlyHistory.all { it.summary.endsWith(" (before Spock connected)") }, "$onlyHistory")
    }

    @Test
    fun `the DevTools link in the first error's full text keeps its token scrubbed`() {
        val first = FlutterErrorReader.read(events.first())

        assertTrue(first.renderedText.contains("inspectorRef=inspector-0"))
        assertFalse(Regex("""%2F[A-Za-z0-9_-]+%3D""").containsMatchIn(first.renderedText), first.renderedText)
    }

    @Test
    fun `nothing is nothing`() {
        val result = FlutterErrorReader.summarise(emptyList())

        assertTrue(result.problems.isEmpty())
        assertNull(result.errorsSinceReload)
    }

    @Test
    fun `a payload of the wrong shape still reads, with what it has`() {
        val odd = listOf(
            FlutterFixtures.event(FlutterExtensionEvent.ERROR, 1, """{}"""),
            FlutterFixtures.event(FlutterExtensionEvent.ERROR, 2, """{"description": 7, "properties": "x"}"""),
            FlutterFixtures.event(
                FlutterExtensionEvent.ERROR,
                3,
                """{"properties": [1, null, {"type": "ErrorSummary"}], "renderedErrorText": ["a"],
                   "errorsSinceReload": "3"}""",
            ),
            FlutterFixtures.event(
                FlutterExtensionEvent.ERROR,
                4,
                """{"description": "Exception caught by widgets library",
                   "renderedErrorText": "Another exception was thrown: Boom"}""",
            ),
        )

        val errors = FlutterErrorReader.read(odd)

        assertEquals(listOf("Flutter error", "Flutter error", "Flutter error"), errors.take(3).map { it.summary })
        assertEquals("Exception caught by widgets library: Boom", errors[3].summary)
        assertEquals(-1, errors[2].errorsSinceReload)
        assertEquals(2, FlutterErrorReader.summarise(odd).problems.size)
    }

    @Test
    fun `a long error is clipped to one line's budget`() {
        val long = "x".repeat(500)
        val event = FlutterFixtures.event(
            FlutterExtensionEvent.ERROR,
            1,
            """{"description": "Exception caught by widgets library",
               "properties": [{"type": "ErrorSummary", "description": "$long"}]}""",
            history = true,
        )

        val summary = FlutterErrorReader.summarise(listOf(event)).problems.single().summary

        assertTrue(summary.length <= DiagnosticShell.MAX_VALUE_CHARS, "${summary.length}")
        assertTrue(summary.endsWith("… (before Spock connected)"), summary)
    }

    @Test
    fun `other event kinds are not errors`() {
        val frames = FlutterFixtures.events(FlutterExtensionEvent.FRAME)

        assertTrue(FlutterErrorReader.read(frames).isEmpty())
    }
}
