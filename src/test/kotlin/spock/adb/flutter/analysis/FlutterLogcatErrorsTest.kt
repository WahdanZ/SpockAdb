package spock.adb.flutter.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.DeviceTime
import java.time.Instant
import java.time.ZoneOffset

/**
 * Against `logcat-structured-errors-off.txt`: the 3.22.2 capture's `renderedErrorText` — the text
 * `FlutterError.presentError` prints — as logcat shows it under the `flutter` tag, then the
 * one-line repeats Flutter prints for every later error, a line from another app's pid, and an
 * ordinary print. The DevTools link carries a fake VM Service token.
 */
class FlutterLogcatErrorsTest {

    private val log = requireNotNull(javaClass.getResource("/flutter/logcat-structured-errors-off.txt")).readText()
    private val utc = DeviceTime(epochOffsetMs = 0, uncertaintyMs = 0, zone = ZoneOffset.UTC)
    private val now = Instant.parse("2026-10-02T14:05:00Z").toEpochMilli()

    private fun read(text: String = log, pids: Set<String> = setOf(APP_PID)) =
        FlutterLogcatErrors.read(text, pids) { utc.logcatToEpoch(it, now) }

    @Test
    fun `the first error is read in full, as the VM Service would report it`() {
        val first = read().first()

        assertEquals(
            "Exception caught by rendering library: A RenderFlex overflowed by 219 pixels on the right." +
                " — Row at lib/fixtures/layout.dart:30:17",
            first.summary,
        )
        assertEquals("assertion", first.thrown)
        assertEquals(FlutterErrorReader.Source.LOGCAT, first.source)
        assertEquals(Instant.parse("2026-10-02T14:03:07.412Z").toEpochMilli(), first.timestampMs)
    }

    @Test
    fun `later errors are the one-line repeats, grouped by what they say`() {
        val result = FlutterErrorReader.summariseErrors(read(), ZoneOffset.UTC)

        assertEquals(3, result.groups.size)
        val tap = result.groups.single { "tap handler" in it.first.summary }
        assertEquals(2, tap.count)
        assertEquals("Another exception was thrown: Bad state: Sample error thrown in a tap handler", tap.first.summary)
        assertEquals("10-02 14:03:09.500", result.problems[result.groups.indexOf(tap)].lastSeen)
        assertTrue(result.problems.all { it.type == FlutterProblemTypes.FLUTTER_ERROR })
    }

    @Test
    fun `only the app's own pid is read`() {
        assertFalse(read().any { "different app" in it.summary })
        assertEquals(1, read(pids = setOf("9999")).size)
    }

    @Test
    fun `no VM Service token survives, even percent-encoded in the DevTools link`() {
        read().forEach { error ->
            assertFalse("AbCdEfGh123" in error.renderedText, error.renderedText)
            assertFalse("AbCdEfGh123" in error.summary, error.summary)
        }
    }

    @Test
    fun `a report cut off by the window's end is still read`() {
        val cut = log.lines().takeWhile { "To inspect" !in it }.joinToString("\n")

        val errors = read(cut)

        assertEquals(1, errors.size)
        assertEquals("A RenderFlex overflowed by 219 pixels on the right.", errors.single().headline)
    }

    @Test
    fun `ordinary prints and an empty log are not errors`() {
        assertTrue(read("10-02 14:03:09.600  4242  4242 I flutter : hello").isEmpty())
        assertTrue(read("").isEmpty())
    }

    @Test
    fun `a widget named on the same line as its location is found too`() {
        val text = listOf(
            "══╡ EXCEPTION CAUGHT BY WIDGETS LIBRARY ╞═══════════════════════",
            "The following ArgumentError was thrown building _ThrowsInBuild:",
            "Invalid argument(s): Sample error thrown in build()",
            "",
            "The relevant error-causing widget was:",
            "  _ThrowsInBuild _ThrowsInBuild:file:///app/lib/fixtures/errors.dart:105:33",
            "═══════════════════════════════════════════════════════════════",
        ).joinToString("\n") { "10-02 14:03:07.412  4242  4242 I flutter : $it" }

        val error = read(text).single()

        assertEquals("ArgumentError", error.thrown)
        assertEquals("_ThrowsInBuild at lib/fixtures/errors.dart:105:33", error.widget)
    }

    private companion object {
        const val APP_PID = "4242"
    }
}
