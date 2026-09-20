package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatLineTest {

    private fun entry(
        message: String = "hello",
        tag: String = "OkHttp",
        pid: Int = 3189,
        level: LogLevel = LogLevel.DEBUG,
        timestamp: String = "09-20 17:01:16.753",
    ) = LogcatEntry(timestamp, pid, pid, level, tag, message, "raw")

    @Test
    fun `a line carries the time, level, tag and message`() {
        val rendered = LogcatLine.render(entry(), previous = null)

        assertTrue(rendered.text.startsWith("17:01:16.753"), rendered.text)
        assertTrue(rendered.text.contains("OkHttp"))
        assertTrue(rendered.text.endsWith("hello"))
        // The date is the same all session; only the time earns the space.
        assertFalse(rendered.text.contains("09-20"))
    }

    @Test
    fun `a repeated tag is blanked but keeps its column`() {
        val first = LogcatLine.render(entry(message = "one"), previous = null)
        val second = LogcatLine.render(entry(message = "two"), previous = entry(message = "one"))

        assertFalse(second.text.contains("OkHttp"))
        assertEquals(
            first.text.indexOf("one"),
            second.text.indexOf("two"),
            "the message column moved when the tag was suppressed",
        )
    }

    @Test
    fun `a tag from another process is not treated as a repeat`() {
        val rendered = LogcatLine.render(entry(), previous = entry(pid = 9999))

        assertTrue(rendered.text.contains("OkHttp"))
    }

    @Test
    fun `a continuation line repeats neither the time nor the tag`() {
        // Two lines of one JSON body, 1ms apart from the same writer.
        val first = entry(message = "{", timestamp = "09-20 17:01:18.639")
        val second = entry(message = "  \"city\": \"berlin\",", timestamp = "09-20 17:01:18.640")

        val rendered = LogcatLine.render(second, previous = first)

        assertFalse(rendered.text.contains("17:01:18.640"), rendered.text)
        assertFalse(rendered.text.contains("OkHttp"), rendered.text)
        // The level belongs to the statement, not to each of its lines.
        assertFalse(rendered.text.trimStart().startsWith("D"), rendered.text)
        // The columns are held open, so the body stays in one place.
        val head = LogcatLine.render(first, previous = null)
        assertEquals(head.text.indexOf("{"), rendered.text.indexOf("  \"city\""))
    }

    @Test
    fun `a new statement prints its time again`() {
        // Same writer, but a second later: a separate statement, not a continuation.
        val first = entry(message = "first", timestamp = "09-20 17:01:18.639")
        val later = entry(message = "second", timestamp = "09-20 17:01:20.100")

        val rendered = LogcatLine.render(later, previous = first)

        assertTrue(rendered.text.contains("17:01:20.100"), rendered.text)
    }

    @Test
    fun `a newline inside a message can never split a record`() {
        // The document's line numbering is the entry index: an embedded newline would shift
        // every entry after it out of step with what is on screen.
        val rendered = LogcatLine.render(entry(message = "first\nsecond\r\nthird"), previous = null)

        assertFalse(rendered.text.contains('\n'))
        assertFalse(rendered.text.contains('\r'))
        assertTrue(rendered.text.contains("first second  third"))
    }

    @Test
    fun `a banner with no timestamp is rendered as itself`() {
        val banner = entry(message = "--------- beginning of main", tag = "", timestamp = "")

        val rendered = LogcatLine.render(banner, previous = null)

        assertEquals("--------- beginning of main", rendered.text)
    }

    @Test
    fun `spans stay inside the line they describe`() {
        val rendered = LogcatLine.render(
            entry(message = "FATAL EXCEPTION: main", tag = "AndroidRuntime", level = LogLevel.ERROR),
            previous = null,
        )

        assertTrue(rendered.spans.isNotEmpty())
        rendered.spans.forEach { span ->
            assertTrue(span.from in 0..rendered.text.length, "span starts outside the line: $span")
            assertTrue(span.to in span.from..rendered.text.length, "span ends outside the line: $span")
        }
    }

    @Test
    fun `an over-long tag is truncated rather than pushing the message out`() {
        val rendered = LogcatLine.render(entry(tag = "AVeryLongTagNameThatGoesOnAndOn"), previous = null)
        val ordinary = LogcatLine.render(entry(tag = "Short"), previous = null)

        assertEquals(
            ordinary.text.indexOf("hello"),
            rendered.text.indexOf("hello"),
            "a long tag moved the message column",
        )
    }
}
