package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatFilterTest {

    private fun entry(
        level: LogLevel = LogLevel.INFO,
        tag: String = "Tag",
        message: String = "message",
        pid: Int = 1000,
    ) = LogcatEntry("10-04 12:00:00.000", pid, pid, level, tag, message, "raw")

    @Test
    fun `level filtering is inclusive of the selected level and above`() {
        val filter = LogcatFilter(minLevel = LogLevel.WARN)

        assertFalse(filter.matches(entry(level = LogLevel.INFO)))
        assertTrue(filter.matches(entry(level = LogLevel.WARN)))
        assertTrue(filter.matches(entry(level = LogLevel.ERROR)))
        assertTrue(filter.matches(entry(level = LogLevel.ASSERT)))
    }

    @Test
    fun `an empty pid set means any process`() {
        assertTrue(LogcatFilter().matches(entry(pid = 42)))
    }

    @Test
    fun `pid filtering keeps only the listed processes`() {
        val filter = LogcatFilter(pids = setOf(100, 200))

        assertTrue(filter.matches(entry(pid = 100)))
        assertFalse(filter.matches(entry(pid = 300)))
    }

    @Test
    fun `plain search matches message or tag, case-insensitively`() {
        val filter = LogcatFilter(query = "boom")

        assertTrue(filter.matches(entry(message = "It went BOOM")))
        assertTrue(filter.matches(entry(tag = "BoomTag", message = "fine")))
        assertFalse(filter.matches(entry(message = "all good")))
    }

    @Test
    fun `regex search matches on the pattern`() {
        val filter = LogcatFilter(query = "err(or)?\\d+", useRegex = true)

        assertTrue(filter.matches(entry(message = "error42 happened")))
        assertTrue(filter.matches(entry(message = "err7")))
        assertFalse(filter.matches(entry(message = "nothing")))
    }

    @Test
    fun `an invalid regex matches nothing and is reported`() {
        // Falling back to matching everything would look like the filter was ignored.
        val filter = LogcatFilter(query = "[unclosed", useRegex = true)

        assertTrue(filter.hasInvalidRegex)
        assertFalse(filter.matches(entry(message = "anything")))
    }

    @Test
    fun `tag filtering narrows by tag only`() {
        val filter = LogcatFilter(tag = "OkHttp")

        assertTrue(filter.matches(entry(tag = "OkHttpClient")))
        assertFalse(filter.matches(entry(tag = "ActivityManager")))
    }

    @Test
    fun `the crashes preset matches a fatal exception header`() {
        val filter = LogcatPreset.CRASHES.toFilter(emptySet())

        val crash = entry(level = LogLevel.ERROR, tag = "AndroidRuntime", message = "FATAL EXCEPTION: main")
        assertTrue(filter.matches(crash))
        assertFalse(filter.matches(entry(level = LogLevel.INFO, message = "just information")))
    }

    @Test
    fun `the ANR preset matches an ANR report`() {
        val filter = LogcatPreset.ANR.toFilter(emptySet())

        assertTrue(filter.matches(entry(message = "ANR in com.example.app")))
        assertTrue(filter.matches(entry(message = "Reason: Input dispatching timed out")))
    }

    @Test
    fun `the current app preset filters by the app's pids`() {
        val filter = LogcatPreset.CURRENT_APP.toFilter(setOf(555))

        assertTrue(filter.matches(entry(pid = 555)))
        assertFalse(filter.matches(entry(pid = 556)))
    }

    @Test
    fun `highlighting classifies crashes, ANRs and levels`() {
        assertEquals(
            LogcatHighlighter.Highlight.CRASH,
            LogcatHighlighter.classify(entry(level = LogLevel.ERROR, message = "FATAL EXCEPTION: main")),
        )
        assertEquals(
            LogcatHighlighter.Highlight.ANR,
            LogcatHighlighter.classify(entry(message = "ANR in com.example.app")),
        )
        assertEquals(
            LogcatHighlighter.Highlight.ERROR,
            LogcatHighlighter.classify(entry(level = LogLevel.ERROR, message = "ordinary error")),
        )
        assertEquals(
            LogcatHighlighter.Highlight.NONE,
            LogcatHighlighter.classify(entry(level = LogLevel.DEBUG)),
        )
    }
}
