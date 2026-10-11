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

    @Test
    fun `a secret near the clip point is redacted before it is cut, not after`() {
        // Clipping first would keep the JWT's first two segments, which its redaction rule
        // (three segments) no longer matches. Assembled so no token-shaped literal is tracked.
        val jwt = listOf("eyJ" + "h".repeat(30), "p".repeat(30), "s".repeat(30)).joinToString(".")
        val result = extract(line(100, 'E', "Auth", "x".repeat(150) + " refresh with " + jwt))

        val summary = result.problems.single().summary
        assertFalse(summary.contains("eyJhhhh"), summary)
        assertTrue(summary.length <= DiagnosticShell.MAX_VALUE_CHARS, summary)
    }

    @Test
    fun `credentials in a URL's userinfo never reach the summary`() {
        val password = listOf("not", "a", "password").joinToString("")
        val result = extract(line(100, 'W', "Net", "GET https://admin:$password@api.example.com/items -> HTTP 401"))

        val summary = result.problems.single().summary
        assertFalse(summary.contains(password), summary)
        assertEquals("GET /items (api.example.com) returned HTTP 401", summary)
    }

    @Test
    fun `an ANR in a package that merely starts with the app's name is not the app's`() {
        val result = extract(
            line(1000, 'E', "ActivityManager", "ANR in com.example.app.debug (com.example.app.debug/.Main)"),
            line(1000, 'E', "ActivityManager", "Reason: Input dispatching timed out"),
        )

        assertTrue(result.problems.isEmpty(), "${result.problems}")
    }

    @Test
    fun `an ANR in the app's own secondary process is still the app's`() {
        val result = extract(line(1000, 'E', "ActivityManager", "ANR in com.example.app:remote"))

        assertEquals("anr", result.problems.single().type)
    }

    /** Captured from `sample/flutter_app` (Errors screen) on an Android 14 emulator, Flutter 3.22. */
    @Test
    fun `Flutter's unhandled exceptions are one problem each, without their Dart frames`() {
        val log = javaClass.getResource("/logcat/flutter-unhandled.txt")!!.readText()
        val problems = LogProblemExtractor.extract(log, "spock.adb.spock_flutter_sample", listOf("14683")).problems

        val summaries = problems.map { it.type to it.summary }
        assertTrue(
            LogProblemExtractor.TYPE_EXCEPTION to
                "Unhandled Dart exception: FormatException: Sample unhandled async error" in summaries,
            "$summaries",
        )
        assertTrue(
            summaries.any { (type, summary) ->
                type == LogProblemExtractor.TYPE_FLUTTER_PLUGIN && summary.startsWith(
                    "Flutter plugin not registered: No implementation found for method ping on channel " +
                        "spock.sample/not_registered",
                )
            },
            "$summaries",
        )
        assertTrue(
            LogProblemExtractor.TYPE_FLUTTER_PLUGIN to
                "Platform channel returned an error: SAMPLE_ERROR — Sample PlatformException from Android" in summaries,
            "$summaries",
        )
        // The native exception a channel handler threw: the Dart side, and the native side once.
        assertTrue(
            summaries.any { it.second.startsWith("Platform channel returned an error: error — Sample native crash") },
            "$summaries",
        )
        val frames = summaries.filter { it.second.contains("#0 ") || it.second.contains("asynchronous suspension") }
        assertTrue(frames.isEmpty(), "$frames")
        assertTrue(problems.all { it.count == 1 }, "$problems")
    }

    private fun flutterHead(exception: String) =
        line(100, 'E', "flutter", "[ERROR:flutter/runtime/dart_vm_initializer.cc(41)] Unhandled Exception: $exception")

    private fun flutterSummary(exception: String) = extract(flutterHead(exception)).problems.single().summary

    @Test
    fun `a second unhandled exception in the same millisecond is its own problem`() {
        val result = extract(
            flutterHead("FormatException: first"),
            line(100, 'E', "flutter", "#0      main (package:app/main.dart:1:1)"),
            flutterHead("StateError: second"),
            line(100, 'E', "flutter", "#0      main (package:app/main.dart:2:1)"),
        )

        assertEquals(
            listOf("Unhandled Dart exception: FormatException: first", "Unhandled Dart exception: StateError: second"),
            result.problems.map { it.summary },
        )
    }

    @Test
    fun `a platform error's message keeps its commas`() {
        assertEquals(
            "Platform channel returned an error: AUTH_FAILED — Sign-in failed, try again later",
            flutterSummary("PlatformException(AUTH_FAILED, Sign-in failed, try again later, null, null)"),
        )
        // The native stack trace starts on the head line and runs over the lines after it.
        assertEquals(
            "Platform channel returned an error: error — Boom, again",
            flutterSummary("PlatformException(error, Boom, again, null, java.lang.IllegalStateException: Boom, again"),
        )
        assertEquals(
            "Platform channel returned an error: NO_MESSAGE",
            flutterSummary("PlatformException(NO_MESSAGE, null, null, null)"),
        )
    }

    /**
     * The messages are the device's (Android 14, Flutter 3.22.2, sample `error_channel_checked`
     * and `error_channel_todo`); pid and stamps are set to one process, a few ms apart.
     */
    @Test
    fun `a checked exception in a channel handler is a handler failure, not a missing plugin`() {
        val log = javaClass.getResource("/logcat/flutter-channel-handler.txt")!!.readText()
        val problems = LogProblemExtractor.extract(log, "spock.adb.spock_flutter_sample", listOf("21877")).problems

        assertEquals(
            listOf(
                LogProblemExtractor.TYPE_FLUTTER_PLUGIN to "Platform channel handler for throwChecked on " +
                    "spock.sample/native threw java.io.IOException: " +
                    "Sample checked exception thrown in a channel handler (inferred from DartMessenger's log)",
                LogProblemExtractor.TYPE_CRASH to
                    "App crashed: kotlin.NotImplementedError: An operation is not implemented: Sample (thread main)",
            ),
            problems.map { it.type to it.summary },
        )
        assertTrue(problems.all { it.count == 1 && it.severity == Severity.ERROR }, "$problems")
        assertEquals("10-01 22:31:04.519", problems.first().lastSeen)
    }

    private val listenerFailure = "Uncaught exception in binary message listener"
    private val noImplementation = "MissingPluginException(No implementation found for method m on channel c)"

    @Test
    fun `a missing plugin long after a handler failure is still a missing plugin`() {
        val result = extract(
            line(100, 'E', "DartMessenger", listenerFailure, time = "09-25 10:00:00.000"),
            line(100, 'E', "DartMessenger", "java.io.IOException: boom", time = "09-25 10:00:00.000"),
            line(
                100,
                'E',
                "flutter",
                "[ERROR:flutter/runtime/dart_vm_initializer.cc(41)] Unhandled Exception: $noImplementation",
                time = "09-25 10:00:05.000",
            ),
        )

        assertEquals(
            listOf(
                "DartMessenger: $listenerFailure — java.io.IOException: boom",
                "Flutter plugin not registered: No implementation found for method m on channel c",
            ),
            result.problems.map { it.summary },
        )
    }

    @Test
    fun `a handler failure in another process does not explain a missing plugin`() {
        val result = extract(
            line(200, 'E', "DartMessenger", listenerFailure),
            line(200, 'E', "DartMessenger", "java.io.IOException: boom"),
            flutterHead(noImplementation),
            pids = listOf("100", "200"),
        )

        assertTrue(result.problems.any { it.summary.startsWith("Flutter plugin not registered") }, "${result.problems}")
    }

    @Test
    fun `a handler failure logged without its exception still explains the missing plugin`() {
        val result = extract(
            line(100, 'E', "DartMessenger", listenerFailure),
            flutterHead(noImplementation),
        )

        assertEquals(
            listOf("Platform channel handler for m on c threw an exception (inferred from DartMessenger's log)"),
            result.problems.map { it.summary },
        )
    }

    @Test
    fun `two handler failures in a row stay two failures with a count`() {
        val failure = arrayOf(
            line(100, 'E', "DartMessenger", listenerFailure),
            line(100, 'E', "DartMessenger", "java.io.IOException: boom"),
            flutterHead(noImplementation),
        )

        val problems = extract(*failure, *failure).problems

        assertEquals(1, problems.size, "$problems")
        assertEquals(2, problems.single().count)
    }

    @Test
    fun `a missing plugin's message keeps its own closing parenthesis`() {
        assertEquals(
            "Flutter plugin not registered: No implementation found for method ping on channel app/x (v2)",
            flutterSummary("MissingPluginException(No implementation found for method ping on channel app/x (v2))"),
        )
    }

    private fun missing(method: String, time: String = "09-25 10:00:00.000") = line(
        100,
        'E',
        "flutter",
        "[ERROR:flutter/runtime/dart_vm_initializer.cc(41)] Unhandled Exception: " +
            "MissingPluginException(No implementation found for method $method on channel c)",
        time = time,
    )

    @Test
    fun `a missing plugin more than half a second after a handler failure is not paired`() {
        val result = extract(
            line(100, 'E', "DartMessenger", listenerFailure, time = "09-25 10:00:00.000"),
            line(100, 'E', "DartMessenger", "java.io.IOException: boom", time = "09-25 10:00:00.000"),
            missing("m", time = "09-25 10:00:00.600"),
        )

        assertTrue(result.problems.any { it.summary.startsWith("Flutter plugin not registered") }, "${result.problems}")
    }

    @Test
    fun `another line of the process between the two breaks the pairing`() {
        val result = extract(
            line(100, 'E', "DartMessenger", listenerFailure),
            line(100, 'E', "DartMessenger", "java.io.IOException: boom"),
            line(100, 'I', "flutter", "user tapped Save"),
            missing("m"),
        )

        assertEquals(
            listOf(
                "DartMessenger: $listenerFailure — java.io.IOException: boom",
                "Flutter plugin not registered: No implementation found for method m on channel c",
            ),
            result.problems.map { it.summary },
        )
    }

    @Test
    fun `two failures before their replies pair first with first`() {
        val result = extract(
            line(100, 'E', "DartMessenger", listenerFailure),
            line(100, 'E', "DartMessenger", "java.io.IOException: first"),
            line(100, 'E', "DartMessenger", "\tat app.Handler.onMethodCall(Handler.kt:12)"),
            line(100, 'E', "DartMessenger", listenerFailure),
            line(100, 'E', "DartMessenger", "java.io.IOException: second"),
            missing("one"),
            line(100, 'E', "flutter", "#0      MethodChannel._invokeMethod (package:flutter/x.dart:1:1)"),
            missing("two", time = "09-25 10:00:00.001"),
        )

        val inferred = " (inferred from DartMessenger's log)"
        assertEquals(
            listOf(
                "Platform channel handler for one on c threw java.io.IOException: first$inferred",
                "Platform channel handler for two on c threw java.io.IOException: second$inferred",
            ),
            result.problems.map { it.summary },
        )
    }

    @Test
    fun `a failure taken back leaves the problem its earlier time`() {
        val result = extract(
            line(100, 'E', "DartMessenger", listenerFailure, time = "09-25 10:00:00.000"),
            line(100, 'E', "DartMessenger", "java.io.IOException: boom", time = "09-25 10:00:00.000"),
            line(100, 'E', "DartMessenger", listenerFailure, time = "09-25 10:00:05.000"),
            line(100, 'E', "DartMessenger", "java.io.IOException: boom", time = "09-25 10:00:05.000"),
            missing("m", time = "09-25 10:00:05.010"),
        )

        val messenger = result.problems.single { it.summary.startsWith("DartMessenger") }
        assertEquals(1, messenger.count)
        assertEquals("09-25 10:00:00.000", messenger.lastSeen)
        assertEquals("09-25 10:00:05.010", result.problems.single { it.summary.startsWith("Platform") }.lastSeen)
    }
}
