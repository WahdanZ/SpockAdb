package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Fixtures use `trimMargin` rather than `trimIndent`: these parsers key off the exact
 * leading whitespace that `dumpsys` emits, which `trimIndent` would strip.
 */
class ApplicationBackStackParserTest {

    @Test
    fun `returns activities most recent first`() {
        val dump = """
            |  ACTIVITY com.example.app/.ListActivity aaa pid=1234
            |    Local Activity 0000 State:
            |      mResumed=false mStopped=true mFinished=false
            |  ACTIVITY com.example.app/.DetailActivity bbb pid=1234
            |    Local Activity 1111 State:
            |      mResumed=true mStopped=false mFinished=false
        """.trimMargin()

        val result = ApplicationBackStackParser.parse(dump)

        assertEquals(
            listOf("com.example.app.DetailActivity", "com.example.app.ListActivity"),
            result.map { it.activity },
        )
    }

    @Test
    fun `records the resumed flag as the activity status`() {
        val dump = """
            |  ACTIVITY com.example.app/.DetailActivity bbb pid=1234
            |    Local Activity 1111 State:
            |      mResumed=true mStopped=false
        """.trimMargin()

        assertEquals("Resumed", ApplicationBackStackParser.parse(dump).single().status)
    }

    @Test
    fun `status reports the first true flag on the state line, not only mResumed`() {
        // The status regex matches any capitalised flag set to true, so a stopped activity
        // is reported as "Stopped". Pinned deliberately: `status` is informational and is
        // not currently consumed by the UI.
        val dump = """
            |  ACTIVITY com.example.app/.DetailActivity bbb pid=1234
            |    Local Activity 1111 State:
            |      mResumed=false mStopped=true
        """.trimMargin()

        assertEquals("Stopped", ApplicationBackStackParser.parse(dump).single().status)
    }

    @Test
    fun `leaves status blank when no flag on the state line is true`() {
        val dump = """
            |  ACTIVITY com.example.app/.DetailActivity bbb pid=1234
            |    Local Activity 1111 State:
            |      mResumed=false mStopped=false
        """.trimMargin()

        assertEquals("", ApplicationBackStackParser.parse(dump).single().status)
    }

    @Test
    fun `does not crash when a fragment line appears before any activity line`() {
        // Regression: the parser called `tasks.last()` unconditionally, throwing
        // NoSuchElementException on a truncated or unexpected dump.
        val dump = """
            |      Active Fragments:
            |        HomeFragment{abc123} (nnn id=0x7f0a0001)
        """.trimMargin()

        val result = assertDoesNotThrow { ApplicationBackStackParser.parse(dump) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list for empty output`() {
        assertTrue(ApplicationBackStackParser.parse("").isEmpty())
    }
}
