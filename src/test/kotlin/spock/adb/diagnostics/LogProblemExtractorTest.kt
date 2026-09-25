package spock.adb.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.LikelyProblem.Severity

/**
 * Each rule against the lines a real device prints. What matters is that one incident becomes
 * one problem — a crash is not also four "log" problems for its stack frames — and that nothing
 * from another app, or from a query string, leaks into a summary.
 */
class LogProblemExtractorTest {

    private val app = "com.example.app"

    private fun line(pid: Int, level: Char, tag: String, message: String, time: String = "09-25 10:00:00.000") =
        "$time  $pid  $pid $level $tag: $message"

    private fun extract(vararg lines: String, pids: List<String> = listOf("100")) =
        LogProblemExtractor.extract(lines.joinToString("\n"), app, pids)

    @Test
    fun `a crash is one problem, from the exception line, with its thread`() {
        val result = extract(
            line(200, 'E', "AndroidRuntime", "FATAL EXCEPTION: main"),
            line(200, 'E', "AndroidRuntime", "Process: com.example.app, PID: 200"),
            line(200, 'E', "AndroidRuntime", "java.lang.IllegalStateException: Sample crash"),
            line(200, 'E', "AndroidRuntime", "\tat com.example.app.Main.onClick(Main.kt:12)"),
            line(200, 'E', "AndroidRuntime", "\tat android.view.View.performClick(View.java:7000)"),
            pids = emptyList(),
        )

        val crash = result.problems.single()
        assertEquals("crash", crash.type)
        assertEquals(Severity.ERROR, crash.severity)
        assertEquals("App crashed: java.lang.IllegalStateException: Sample crash (thread main)", crash.summary)
    }

    @Test
    fun `another app's crash is not this app's problem`() {
        val result = extract(
            line(300, 'E', "AndroidRuntime", "FATAL EXCEPTION: main"),
            line(300, 'E', "AndroidRuntime", "Process: com.other.app, PID: 300"),
            line(300, 'E', "AndroidRuntime", "java.lang.RuntimeException: not ours"),
            line(301, 'E', "OtherTag", "also not ours"),
        )

        assertTrue(result.problems.isEmpty(), "${result.problems}")
        assertEquals(0, result.appLines)
    }

    @Test
    fun `an ANR printed by the system is attributed to the app, with its reason`() {
        val result = extract(
            line(1000, 'E', "ActivityManager", "ANR in com.example.app (com.example.app/.MainActivity)"),
            line(1000, 'E', "ActivityManager", "PID: 100"),
            line(1000, 'E', "ActivityManager", "Reason: Input dispatching timed out"),
        )

        val anr = result.problems.single()
        assertEquals("anr", anr.type)
        assertTrue(anr.summary.endsWith("— Input dispatching timed out"), anr.summary)
    }

    @Test
    fun `an OkHttp failure names the method, the path and the status, but not the query`() {
        val result = extract(
            line(100, 'I', "okhttp.OkHttpClient", "--> POST https://api.example.com/payment?token=s3cret"),
            line(
                100,
                'I',
                "okhttp.OkHttpClient",
                "<-- 500 Internal Server Error https://api.example.com/payment?token=s3cret (84ms)"
            ),
        )

        val failure = result.problems.single()
        assertEquals("network", failure.type)
        assertEquals(Severity.ERROR, failure.severity)
        assertEquals("POST /payment (api.example.com) returned HTTP 500", failure.summary)
        assertFalse(failure.summary.contains("s3cret"))
    }

    @Test
    fun `a 4xx is a warning, a 2xx is not a problem at all`() {
        val result = extract(
            line(100, 'I', "Net", "GET https://api.example.com/items -> HTTP 404 Not Found"),
            line(100, 'I', "Net", "GET https://api.example.com/other -> HTTP 200 OK"),
        )

        assertEquals(Severity.WARNING, result.problems.single().severity)
    }

    @Test
    fun `a logged exception joins the message that introduced it`() {
        val result = extract(
            line(100, 'E', "Checkout", "Could not parse the amount"),
            line(100, 'E', "Checkout", "java.lang.NumberFormatException: For input string: \"12,50\""),
            line(100, 'E', "Checkout", "\tat java.lang.Double.parseDouble(Double.java:1)"),
        )

        val problem = result.problems.single()
        assertEquals("exception", problem.type)
        assertEquals(
            "Checkout: Could not parse the amount — java.lang.NumberFormatException: For input string: \"12,50\"",
            problem.summary,
        )
    }

    @Test
    fun `a network exception is typed as network`() {
        val result = extract(
            line(100, 'W', "Sync", "java.net.UnknownHostException: Unable to resolve host \"api.example.com\""),
        )

        assertEquals("network", result.problems.single().type)
    }

    @Test
    fun `the same error repeated is one problem with a count`() {
        val result = extract(
            *(1..40).map { line(100, 'E', "Cache", "Eviction failed for entry $it") }.toTypedArray(),
        )

        val problem = result.problems.single()
        assertEquals(40, problem.count)
        assertEquals(40, result.errorLines)
    }

    @Test
    fun `secrets are redacted from summaries`() {
        // Assembled rather than written out: a token-shaped literal in a tracked file trips secret
        // scanners, and this one only has to look like a token to the redactor.
        val token = listOf("not", "a", "real", "token").joinToString("-")
        val result = extract(line(100, 'E', "Auth", "Refresh failed, Authorization: Bearer $token"))

        assertFalse(result.problems.single().summary.contains(token), result.problems.single().summary)
    }

    @Test
    fun `every summary stays one bounded line`() {
        val result = extract(line(100, 'E', "Huge", "x".repeat(5_000)))

        assertTrue(result.problems.single().summary.length <= DiagnosticShell.MAX_VALUE_CHARS)
    }

    @Test
    fun `lines that are not threadtime are ignored rather than misread`() {
        val result = LogProblemExtractor.extract("--------- beginning of crash\ngarbage", app, listOf("100"))

        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `with no app known, every line counts`() {
        val result = LogProblemExtractor.extract(line(999, 'E', "Any", "boom"), null, emptyList())

        assertEquals(1, result.problems.size)
    }
}
