package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatGroupTest {

    private fun entry(
        time: String,
        message: String,
        tag: String = "ApiClient",
        pid: Int = 3189,
        level: LogLevel = LogLevel.DEBUG,
    ) = LogcatEntry("09-20 $time", pid, pid, level, tag, message, "raw $message")

    /** One `Log.d` with an embedded JSON body: one statement, one record per line. */
    private val jsonBody = listOf(
        entry("17:01:18.100", "GET /offers", tag = "OkHttp"),
        entry("17:01:18.639", "Response body:"),
        entry("17:01:18.639", "{"),
        entry("17:01:18.639", "  \"city\": \"berlin\","),
        entry("17:01:18.640", "  \"count\": 12"),
        entry("17:01:18.640", "}"),
        entry("17:01:25.000", "Offers loaded", tag = "OfferRepository"),
    )

    @Test
    fun `clicking any line of a block returns the whole block`() {
        val fromMiddle = LogcatGroup.at(jsonBody, 3)

        assertEquals(LogcatGroup.Kind.BURST, fromMiddle.kind)
        assertEquals(5, fromMiddle.entries.size)
        assertTrue(fromMiddle.render().startsWith("Response body:"))
        assertTrue(fromMiddle.render().endsWith("}"))
    }

    @Test
    fun `every line of a block returns the same block`() {
        val rendered = (1..5).map { LogcatGroup.at(jsonBody, it).render() }

        assertEquals(1, rendered.distinct().size, "the block depends on which line was clicked")
    }

    @Test
    fun `a neighbouring tag is not part of the block`() {
        val group = LogcatGroup.at(jsonBody, 1)

        assertFalse(group.render().contains("GET /offers"))
        assertFalse(group.render().contains("Offers loaded"))
    }

    @Test
    fun `a lone line is a group of one`() {
        val group = LogcatGroup.at(jsonBody, 0)

        assertEquals(LogcatGroup.Kind.SINGLE, group.kind)
        assertFalse(group.isMultiLine)
    }

    @Test
    fun `a pause between lines splits the block`() {
        // Same tag and process, a second apart: two statements, not one.
        val twoBursts = listOf(
            entry("17:01:18.100", "first line"),
            entry("17:01:18.150", "still the first"),
            entry("17:01:19.500", "a second statement"),
            entry("17:01:19.530", "still the second"),
        )

        assertEquals(2, LogcatGroup.at(twoBursts, 0).entries.size)
        assertEquals(2, LogcatGroup.at(twoBursts, 3).entries.size)
    }

    @Test
    fun `another process writing the same tag does not join`() {
        val mixed = listOf(
            entry("17:01:18.100", "mine"),
            entry("17:01:18.110", "theirs", pid = 9999),
        )

        assertFalse(LogcatGroup.at(mixed, 0).isMultiLine)
    }

    @Test
    fun `a stack trace is recognised as a trace, not a burst`() {
        fun frame(time: String, message: String) =
            entry(time, message, tag = "AndroidRuntime", level = LogLevel.ERROR)

        val crash = listOf(
            frame("17:01:40.072", "FATAL EXCEPTION: main"),
            frame("17:01:40.088", "java.lang.IllegalStateException: null"),
            frame("17:01:40.088", "\tat com.example.Foo.bar(Foo.kt:42)"),
            frame("17:01:40.094", "\t... 16 more"),
        )

        val group = LogcatGroup.at(crash, 2)

        assertEquals(LogcatGroup.Kind.STACK_TRACE, group.kind)
        assertTrue(group.render().startsWith("java.lang.IllegalStateException"))
    }

    @Test
    fun `an unparsed banner groups with nothing`() {
        val banner = LogcatEntry("", 0, 0, LogLevel.INFO, "", "--------- beginning of main", "raw")

        val group = LogcatGroup.at(listOf(banner, banner), 0)

        assertFalse(group.isMultiLine)
    }

    @Test
    fun `a chosen set of rows is its own kind of group`() {
        val chosen = listOf(jsonBody[0], jsonBody[6])

        val group = LogcatGroup.ofSelection(chosen)

        assertEquals(LogcatGroup.Kind.SELECTION, group.kind)
        assertTrue(group.isMultiLine)
        // Raw for a bug report, messages for reading: a selection offers both.
        assertTrue(group.renderRaw().contains("raw GET /offers"))
        assertEquals("GET /offers\nOffers loaded", group.render())
    }

    @Test
    fun `an out of range index is not an error`() {
        assertTrue(LogcatGroup.at(jsonBody, -1).entries.isEmpty())
        assertTrue(LogcatGroup.at(jsonBody, 99).entries.isEmpty())
    }
}
