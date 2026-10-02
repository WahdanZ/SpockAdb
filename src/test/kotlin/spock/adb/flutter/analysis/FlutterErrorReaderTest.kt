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
        // The last event says 4 errors came before it: five since the reload, itself included.
        assertEquals(5, result.errorsSinceReload)
        // Device epoch ms, for lining a group up with logcat.
        assertEquals(1790887895144, result.groups[0].firstSeenMs)
        assertEquals(result.groups[0].last.timestampMs, result.groups[0].lastSeenMs)
    }

    @Test
    fun `the same overflow at two sites is two problems, at one site with other numbers one`() {
        fun overflow(at: Long, pixels: Int, line: Int) = FlutterFixtures.event(
            FlutterExtensionEvent.ERROR,
            at,
            """{"description": "Exception caught by rendering library", "properties": [
                 {"type": "ErrorSummary", "description": "A RenderFlex overflowed by $pixels pixels on the right."},
                 {"name": "The relevant error-causing widget was", "children": [
                   {"description": "Row Row:file:///home/dev/app/lib/screen.dart:$line:17"}]}]}""",
        )

        val result = FlutterErrorReader.summarise(
            listOf(overflow(1, 219, 30), overflow(2, 12, 30), overflow(3, 219, 31)),
            ZoneOffset.UTC,
        )

        assertEquals(listOf(2, 1), result.problems.map { it.count })
        assertTrue(result.problems[0].summary.endsWith("Row at lib/screen.dart:30:17"), result.problems[0].summary)
        assertTrue(result.problems[1].summary.endsWith("Row at lib/screen.dart:31:17"), result.problems[1].summary)
    }

    @Test
    fun `a headline over several lines reads as one`() {
        val event = FlutterFixtures.event(
            FlutterExtensionEvent.ERROR,
            1,
            """{"description": "Exception caught by widgets library", "properties": [{"type": "ErrorSummary",
               "description": "'package:app/a.dart': Failed assertion: line 3 pos 7: 'x':\n   is not true.\n"}]}""",
        )

        assertEquals(
            "'package:app/a.dart': Failed assertion: line 3 pos 7: 'x': is not true.",
            FlutterErrorReader.read(event).headline,
        )
    }

    @Test
    fun `a raw VM Service token and URL queries never leave the reader or the timeline`() {
        val token = "AbCdEf12_xYz"
        val link = "http://127.0.0.1:9102/#/inspector?uri=http%3A%2F%2F127.0.0.1%3A52511%2F$token%3D%2F" +
            "&inspectorRef=inspector-0"
        val event = FlutterFixtures.event(
            FlutterExtensionEvent.ERROR,
            1,
            """{"description": "Exception caught by image resource service", "properties": [{"type": "ErrorSummary",
               "description": "HTTP request failed, statusCode: 403, https://cdn.example.com/a.png?sig=s3cr3t"}],
               "renderedErrorText": "To inspect this widget in Flutter DevTools, visit: $link\nThe image is gone."}""",
        )

        val error = FlutterErrorReader.read(event)
        val problem = FlutterErrorReader.summarise(listOf(event)).problems.single()
        val row = FlutterTimelineMapper.error(error)

        val texts = listOf(error.headline!!, error.renderedText, problem.summary, row.title, row.detail)
        texts.forEach { text ->
            assertFalse(text.contains(token), text)
            assertFalse(text.contains("s3cr3t"), text)
        }
        assertTrue(error.headline!!.endsWith("https://cdn.example.com/a.png"), error.headline)
        assertTrue(error.renderedText.contains("visit: http://127.0.0.1:9102/\n"), error.renderedText)
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
    fun `the DevTools link in the first error's full text loses its query, token and all`() {
        val first = FlutterErrorReader.read(events.first())

        val link = "in Flutter DevTools, visit:\nhttp://127.0.0.1:9102/\n"
        assertTrue(first.renderedText.contains(link), first.renderedText)
        assertFalse(first.renderedText.contains("uri="), first.renderedText)
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
