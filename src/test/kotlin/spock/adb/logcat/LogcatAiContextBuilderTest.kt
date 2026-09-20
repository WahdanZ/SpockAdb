package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatAiContextBuilderTest {

    private fun entry(
        message: String,
        level: LogLevel = LogLevel.INFO,
        tag: String = "MainActivity",
        pid: Int = 3189,
    ) = LogcatEntry("10-04 12:00:00.000", pid, pid, level, tag, message, "raw $message")

    private fun crash(message: String) = entry(message, LogLevel.ERROR, "AndroidRuntime")

    private fun noise(count: Int, prefix: String = "line") =
        (1..count).map { entry("$prefix $it") }

    @Test
    fun `a selection is used in preference to the visible log`() {
        val context = LogcatAiContextBuilder.build(
            LogcatAiRequest(
                selected = listOf(entry("the one I picked")),
                visible = noise(50),
            ),
        )

        assertTrue(context.usedSelection)
        assertEquals(1, context.lineCount)
        assertTrue(context.text.contains("the one I picked"))
        assertFalse(context.text.contains("line 1\n"))
        assertTrue(context.text.contains("Source: the selected lines"))
    }

    @Test
    fun `without a selection the filtered view is used`() {
        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = noise(5)))

        assertFalse(context.usedSelection)
        assertEquals(5, context.lineCount)
        assertTrue(context.text.contains("Source: the filtered view"))
    }

    @Test
    fun `an empty view produces no lines rather than an apology`() {
        val context = LogcatAiContextBuilder.build(LogcatAiRequest())

        assertEquals(0, context.lineCount)
        assertFalse(context.truncated)
        assertNull(context.focus)
    }

    @Test
    fun `the header states what was being looked at`() {
        val context = LogcatAiContextBuilder.build(
            LogcatAiRequest(
                visible = noise(3),
                appPackage = "com.example.app",
                deviceLabel = "Pixel 8 · Android 14",
                filter = LogcatFilter(
                    scope = LogcatScope.RELATED,
                    intent = LogcatIntent.NETWORK,
                    minLevel = LogLevel.WARN,
                    query = "checkout",
                ),
            ),
        )

        assertTrue(context.text.startsWith("Spock ADB — Logcat context"))
        assertTrue(context.text.contains("App: com.example.app"))
        assertTrue(context.text.contains("Device: Pixel 8 · Android 14"))
        assertTrue(context.text.contains("Scope: Related"))
        assertTrue(context.text.contains("Level: Warn+"))
        assertTrue(context.text.contains("Showing: Network"))
        assertTrue(context.text.contains("Search: checkout"))
        assertTrue(context.text.contains("Lines: 3"))
    }

    @Test
    fun `the line cap keeps the most recent lines and says it truncated`() {
        val context = LogcatAiContextBuilder.build(
            LogcatAiRequest(visible = noise(500), maxLines = 20),
        )

        assertEquals(20, context.lineCount)
        assertTrue(context.truncated)
        assertTrue(context.text.contains(": line 500"))
        assertFalse(context.text.contains(": line 1\n"))
        assertTrue(context.text.contains(": line 481"))
        assertTrue(context.text.contains("Truncated:"))
    }

    @Test
    fun `the byte cap is honoured even when the line cap is not reached`() {
        val context = LogcatAiContextBuilder.build(
            LogcatAiRequest(visible = noise(200), maxLines = 200, maxBytes = 500),
        )

        assertTrue(context.truncated)
        assertTrue(context.text.substringAfter("Relevant logs:\n").toByteArray().size <= 500)
    }

    @Test
    fun `consecutive repeats are collapsed with a count`() {
        val repeated = (1..40).map { entry("Skipped frames") }

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = repeated))

        assertEquals(1, context.lineCount)
        assertTrue(context.text.contains("(repeated 40×)"))
    }

    @Test
    fun `repeats are only collapsed when they are adjacent`() {
        val alternating = listOf(entry("a"), entry("b"), entry("a"))

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = alternating))

        assertEquals(3, context.lineCount)
    }

    @Test
    fun `the window is centred on the most recent crash rather than on the tail`() {
        val visible = noise(50, "before") + crash("FATAL EXCEPTION: main") + noise(150, "after")

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible, maxLines = 20))

        assertEquals(LogcatAiContextBuilder.Focus.CRASH, context.focus)
        assertTrue(context.text.contains("FATAL EXCEPTION: main"))
        // The tail is deliberately not what was sent: the crash is.
        assertFalse(context.text.contains("after 150"))
        assertTrue(context.text.contains("Focus: the window is centred on the most recent crash"))
    }

    @Test
    fun `an ANR is a focus too`() {
        val visible = noise(30) + entry("ANR in com.example.app", LogLevel.ERROR, "ActivityManager")

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible, maxLines = 10))

        assertEquals(LogcatAiContextBuilder.Focus.ANR, context.focus)
        assertTrue(context.text.contains("ANR in com.example.app"))
    }

    @Test
    fun `a stack trace is not sent without the exception it belongs to`() {
        val visible = noise(50, "before") +
            crash("java.lang.IllegalStateException: Required value was null") +
            (1..20).map { crash("\tat com.example.Offers.load(Offers.kt:$it)") } +
            noise(50, "after")

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible, maxLines = 20))

        assertTrue(
            context.text.contains("java.lang.IllegalStateException"),
            "the window should stretch back to the exception header:\n${context.text}",
        )
    }

    @Test
    fun `frames whose header did not fit are dropped rather than sent alone`() {
        val visible = noise(50, "before") +
            crash("java.lang.IllegalStateException: Required value was null") +
            (1..200).map { crash("\tat com.example.Offers.load(Offers.kt:$it)") } +
            noise(40, "after")

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible, maxLines = 30))

        val firstLog = context.text.substringAfter("Relevant logs:\n").lineSequence().first()
        assertFalse(firstLog.contains("\tat com.example"), "started mid-trace: $firstLog")
    }

    @Test
    fun `credentials are redacted and the redaction is declared`() {
        val visible = listOf(entry("Authorization: Bearer abcdef1234567890", tag = "OkHttp"))

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible))

        assertFalse(context.text.contains("abcdef1234567890"))
        assertTrue(context.redactions > 0)
        assertTrue(context.text.contains("Redacted:"))
    }

    @Test
    fun `empty records do not spend the budget`() {
        val visible = listOf(entry("real line"), entry(""), entry("another real line"), entry("   "))

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible))

        assertEquals(2, context.lineCount)
    }

    @Test
    fun `a scope that could not be applied says so`() {
        // The App scope with no resolved PIDs matches every process, which is right for the
        // panel and misleading in the context: a model would read system lines as the app's.
        val context = LogcatAiContextBuilder.build(
            LogcatAiRequest(
                visible = noise(3),
                filter = LogcatFilter(scope = LogcatScope.APP, appPids = emptySet()),
            ),
        )

        assertTrue(context.text.contains("Scope: App — not applied"), context.text)
    }

    @Test
    fun `a scope that was applied is stated without a caveat`() {
        val context = LogcatAiContextBuilder.build(
            LogcatAiRequest(
                visible = noise(3),
                filter = LogcatFilter(scope = LogcatScope.APP, appPids = setOf(3189)),
            ),
        )

        assertTrue(context.text.contains("Scope: App\n"), context.text)
        assertFalse(context.text.contains("not applied"))
    }

    @Test
    fun `folding is not reported as truncation`() {
        val visible = listOf(entry("a"), entry("a"), entry("a"), entry(""), entry("b"))

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = visible))

        assertFalse(context.truncated)
        assertTrue(context.text.contains("folded from 5 records"), context.text)
        assertFalse(context.text.contains("Truncated:"))
    }

    @Test
    fun `an enormous line is capped instead of spending the budget`() {
        val huge = "lib" + "x".repeat(4_000) + ".so"

        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = listOf(entry(huge))))

        val body = context.text.substringAfter("Relevant logs:\n")
        assertTrue(body.length < 1_000, "line was not capped: ${body.length} chars")
        assertTrue(body.contains("more characters)"), body)
    }

    @Test
    fun `the prompt asks the question the context was built to answer`() {
        val context = LogcatAiContextBuilder.build(LogcatAiRequest(visible = noise(2)))

        assertTrue(context.asPrompt().startsWith(LogcatAiContextBuilder.QUESTION))
        assertTrue(context.asPrompt().contains("Spock ADB — Logcat context"))
    }

    @Test
    fun `the same input always produces the same context`() {
        val request = LogcatAiRequest(visible = noise(80) + crash("FATAL EXCEPTION: main"), maxLines = 30)

        assertEquals(
            LogcatAiContextBuilder.build(request).text,
            LogcatAiContextBuilder.build(request).text,
        )
    }
}
