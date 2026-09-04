package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogcatParserTest {

    @Test
    fun `parses a threadtime record`() {
        val entry = LogcatParser.parse("10-04 12:34:56.789  1234  1250 E MyTag: something went wrong")!!

        assertEquals("10-04 12:34:56.789", entry.timestamp)
        assertEquals(1234, entry.pid)
        assertEquals(1250, entry.tid)
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("MyTag", entry.tag)
        assertEquals("something went wrong", entry.message)
    }

    @Test
    fun `maps every level code including fatal`() {
        mapOf(
            'V' to LogLevel.VERBOSE,
            'D' to LogLevel.DEBUG,
            'I' to LogLevel.INFO,
            'W' to LogLevel.WARN,
            'E' to LogLevel.ERROR,
            'A' to LogLevel.ASSERT,
            // The device prints F for what the API calls ASSERT.
            'F' to LogLevel.ASSERT,
        ).forEach { (code, expected) ->
            val entry = LogcatParser.parse("10-04 12:00:00.000  1  1 $code Tag: msg")!!
            assertEquals(expected, entry.level, "code $code")
        }
    }

    @Test
    fun `handles tags containing spaces and colons in the message`() {
        val entry = LogcatParser.parse("10-04 12:00:00.000  1  1 I My Tag: a: b: c")!!

        assertEquals("My Tag", entry.tag)
        assertEquals("a: b: c", entry.message)
    }

    @Test
    fun `handles an empty message`() {
        val entry = LogcatParser.parse("10-04 12:00:00.000  1  1 I Tag:")!!
        assertEquals("", entry.message)
    }

    @Test
    fun `keeps the raw line so copy and export reproduce the device output`() {
        val raw = "10-04 12:34:56.789  1234  1250 E MyTag: boom"
        assertEquals(raw, LogcatParser.parse(raw)!!.raw)
    }

    @Test
    fun `surfaces logcat banners instead of dropping them`() {
        // Silently swallowing lines makes the panel untrustworthy.
        val entry = LogcatParser.parse("--------- beginning of crash")!!

        assertEquals("--------- beginning of crash", entry.message)
        assertEquals("", entry.tag)
    }

    @Test
    fun `ignores blank lines`() {
        assertNull(LogcatParser.parse(""))
        assertNull(LogcatParser.parse("    "))
    }
}
