package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatStackTraceTest {

    private fun crashLine(message: String, pid: Int = 3189) =
        LogcatEntry("10-04 12:00:00.000", pid, pid, LogLevel.ERROR, "AndroidRuntime", message, message)

    private fun info(message: String) =
        LogcatEntry("10-04 12:00:00.000", 3189, 3189, LogLevel.INFO, "MainActivity", message, message)

    private val trace = listOf(
        info("Screen opened"),
        crashLine("FATAL EXCEPTION: main"),
        crashLine("java.lang.IllegalStateException: Required value was null"),
        crashLine("\tat com.example.OffersViewModel.loadOffers(OffersViewModel.kt:42)"),
        crashLine("\tat kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)"),
        crashLine("\t... 16 more"),
        info("Process ended"),
    )

    @Test
    fun `a frame resolves to the whole report above and below it`() {
        val found = LogcatStackTrace.at(trace, 3)

        assertEquals(4, found.size)
        assertTrue(found.first().message.contains("IllegalStateException"))
        assertTrue(found.last().message.contains("16 more"))
    }

    @Test
    fun `the exception header resolves to its own frames`() {
        val found = LogcatStackTrace.at(trace, 2)

        assertEquals(4, found.size)
    }

    @Test
    fun `an ordinary line has no trace`() {
        assertTrue(LogcatStackTrace.at(trace, 0).isEmpty())
        assertTrue(LogcatStackTrace.at(trace, 6).isEmpty())
    }

    @Test
    fun `an out of range index is not an error`() {
        assertTrue(LogcatStackTrace.at(trace, -1).isEmpty())
        assertTrue(LogcatStackTrace.at(trace, 99).isEmpty())
    }

    @Test
    fun `frames from another process do not join the trace`() {
        val mixed = trace.take(4) + crashLine("\tat com.other.Thing.run(Thing.kt:1)", pid = 4242)

        val found = LogcatStackTrace.at(mixed, 3)

        assertEquals(2, found.size)
    }

    @Test
    fun `rendering keeps the messages and drops the log furniture`() {
        val rendered = LogcatStackTrace.render(LogcatStackTrace.at(trace, 3))

        assertTrue(rendered.startsWith("java.lang.IllegalStateException"))
        assertTrue(rendered.lines().size == 4)
    }
}
