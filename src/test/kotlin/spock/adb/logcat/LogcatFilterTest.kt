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
    fun `an empty pid set means the app is not resolved yet, so nothing is hidden`() {
        assertTrue(LogcatFilter(scope = LogcatScope.APP).matches(entry(pid = 42)))
    }

    @Test
    fun `the app scope keeps only the app's processes`() {
        val filter = LogcatFilter(scope = LogcatScope.APP, appPids = setOf(100, 200))

        assertTrue(filter.matches(entry(pid = 100)))
        assertFalse(filter.matches(entry(pid = 300)))
    }

    @Test
    fun `the related scope adds the system components that act on the app`() {
        val filter = LogcatFilter(scope = LogcatScope.RELATED, appPids = setOf(100))

        assertTrue(filter.matches(entry(pid = 100, tag = "MyTag")))
        assertTrue(filter.matches(entry(pid = 9, tag = "ActivityManager")))
        assertTrue(filter.matches(entry(pid = 9, tag = "ConnectivityService")))
        assertTrue(filter.matches(entry(pid = 9, tag = "AndroidRuntime")))
    }

    @Test
    fun `the related scope does not admit unrelated system noise`() {
        val filter = LogcatFilter(scope = LogcatScope.RELATED, appPids = setOf(100))

        assertFalse(filter.matches(entry(pid = 9, tag = "SensorService")))
        assertFalse(filter.matches(entry(pid = 9, tag = "audio_hw_primary")))
        assertFalse(filter.matches(entry(pid = 9, tag = "StatsCompanionService")))
    }

    @Test
    fun `the related scope keeps a system line that names the app`() {
        val filter = LogcatFilter(
            scope = LogcatScope.RELATED,
            appPids = setOf(100),
            appPackage = "com.example.app",
        )

        assertTrue(filter.matches(entry(pid = 9, tag = "Whatever", message = "ANR in com.example.app")))
        assertFalse(filter.matches(entry(pid = 9, tag = "Whatever", message = "ANR in com.other.app")))
    }

    @Test
    fun `the all scope keeps every process`() {
        val filter = LogcatFilter(scope = LogcatScope.ALL, appPids = setOf(100))

        assertTrue(filter.matches(entry(pid = 12345, tag = "SensorService")))
    }

    @Test
    fun `an intent narrows inside the scope and never widens it`() {
        val crash = entry(pid = 999, level = LogLevel.ERROR, tag = "AndroidRuntime", message = "FATAL EXCEPTION: main")
        val filter = LogcatFilter(
            scope = LogcatScope.APP,
            intent = LogcatIntent.CRASHES,
            appPids = setOf(100),
        )

        // The crash belongs to another process: choosing "Crashes" must not pull it in.
        assertFalse(filter.matches(crash))
        assertTrue(filter.matches(crash.copy(pid = 100)))
    }

    @Test
    fun `scope and intent combine`() {
        val network = entry(pid = 100, tag = "OkHttp", message = "GET /offers")
        val error = entry(pid = 100, level = LogLevel.ERROR, tag = "Repo", message = "failed")

        val appNetwork = LogcatFilter(
            scope = LogcatScope.APP,
            intent = LogcatIntent.NETWORK,
            appPids = setOf(100),
        )
        assertTrue(appNetwork.matches(network))
        assertFalse(appNetwork.matches(error))

        val relatedErrors = LogcatFilter(
            scope = LogcatScope.RELATED,
            intent = LogcatIntent.ERRORS,
            appPids = setOf(100),
        )
        assertTrue(relatedErrors.matches(error))
        assertFalse(relatedErrors.matches(network))
    }

    @Test
    fun `the crashes intent keeps the frames beneath the header`() {
        val filter = LogcatFilter(intent = LogcatIntent.CRASHES, scope = LogcatScope.ALL)

        val header = entry(level = LogLevel.ERROR, tag = "AndroidRuntime", message = "FATAL EXCEPTION: main")
        assertTrue(filter.matches(header))
        assertTrue(
            filter.matches(
                entry(level = LogLevel.ERROR, tag = "AndroidRuntime", message = "\tat com.example.Foo.bar(Foo.kt:42)"),
            ),
        )
        assertFalse(filter.matches(entry(level = LogLevel.INFO, message = "just information")))
    }

    @Test
    fun `the ANR intent matches an ANR report`() {
        val filter = LogcatFilter(intent = LogcatIntent.ANRS, scope = LogcatScope.ALL)

        assertTrue(filter.matches(entry(message = "ANR in com.example.app")))
        assertTrue(filter.matches(entry(message = "Reason: Input dispatching timed out")))
        assertFalse(filter.matches(entry(message = "all good")))
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
    fun `a record with no message is not shown`() {
        // The device emits them: every `adb shell log` ends with one, and they are blank rows.
        assertFalse(LogcatFilter().matches(entry(message = "")))
        assertFalse(LogcatFilter().matches(entry(message = "   ")))
        assertTrue(LogcatFilter().matches(entry(message = "something")))
    }

    @Test
    fun `a scope is applied only when it can be`() {
        assertFalse(LogcatFilter(scope = LogcatScope.APP, appPids = emptySet()).isScopeApplied)
        assertFalse(LogcatFilter(scope = LogcatScope.RELATED, appPids = emptySet()).isScopeApplied)
        assertTrue(LogcatFilter(scope = LogcatScope.APP, appPids = setOf(1)).isScopeApplied)
        // "All" is always exactly what it says.
        assertTrue(LogcatFilter(scope = LogcatScope.ALL, appPids = emptySet()).isScopeApplied)
    }

    @Test
    fun `describe names the scope and the filter separately`() {
        val described = LogcatFilter(
            scope = LogcatScope.RELATED,
            intent = LogcatIntent.CRASHES,
            minLevel = LogLevel.WARN,
            query = "checkout",
        ).describe()

        assertTrue(described.contains("Scope: Related"), described)
        assertTrue(described.contains("Warn+"), described)
        assertTrue(described.contains("Crashes"), described)
        assertTrue(described.contains("checkout"), described)
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
