package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppProcessTrackerTest {

    private val packageName = "com.example.app"

    private fun line(message: String, tag: String = "ActivityManager") =
        LogcatEntry("09-20 17:01:16.753", 551, 674, LogLevel.INFO, tag, message, "raw")

    private val running = AppProcesses(AppProcesses.State.RUNNING, setOf(3189), packageName)

    @Test
    fun `a relaunch replaces the dead process with the new one`() {
        // The case that made App scope silently hide the app's own logs: pidof answers once,
        // the process restarts, and the old PID is all the filter knows.
        val died = AppProcessTracker.apply(running, line("Process $packageName (pid 3189) has died: fg TOP"))
        assertEquals(AppProcesses.State.NOT_RUNNING, died?.state)
        assertFalse(died!!.contains(3189))

        val restarted = AppProcessTracker.apply(died, line("Start proc 4201:$packageName/u0a188 for activity"))
        assertEquals(AppProcesses.State.RUNNING, restarted?.state)
        assertTrue(restarted!!.contains(4201))
        assertFalse(restarted.contains(3189))
    }

    @Test
    fun `an older device puts the pid at the end of the line`() {
        val started = AppProcessTracker.startedPid(
            line("Start proc $packageName for activity $packageName/.MainActivity: pid=5150 uid=10188"),
            packageName,
        )

        assertEquals(5150, started)
    }

    @Test
    fun `another app starting or dying changes nothing`() {
        assertNull(AppProcessTracker.apply(running, line("Start proc 999:com.other.app/u0a1 for activity")))
        assertNull(AppProcessTracker.apply(running, line("Process com.other.app (pid 999) has died")))
    }

    @Test
    fun `a package that only shares a prefix is not this app`() {
        assertNull(AppProcessTracker.startedPid(line("Start proc 77:com.example.app2/u0a1 for activity"), packageName))
        assertNull(AppProcessTracker.diedPid(line("Process com.example.apple (pid 77) has died"), packageName))
    }

    @Test
    fun `a second process of the same app is added, not swapped`() {
        val extra = AppProcessTracker.apply(running, line("Start proc 4300:$packageName/u0a188 for service"))

        assertTrue(extra!!.contains(3189))
        assertTrue(extra.contains(4300))
    }

    @Test
    fun `a death already accounted for is not reported twice`() {
        val once = AppProcessTracker.apply(running, line("Process $packageName (pid 3189) has died"))

        assertNull(AppProcessTracker.apply(once!!, line("Process $packageName (pid 3189) has died")))
    }

    @Test
    fun `ordinary lines cost nothing`() {
        assertNull(AppProcessTracker.apply(running, line("Displayed 12 offers", tag = "ActivityTaskManager")))
        assertNull(AppProcessTracker.apply(running, line("GET /offers", tag = "OkHttp")))
    }

    @Test
    fun `nothing is tracked without a package to track`() {
        val unknown = AppProcesses.UNKNOWN

        assertNull(AppProcessTracker.apply(unknown, line("Start proc 4201:$packageName/u0a188 for activity")))
    }
}
