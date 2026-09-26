package spock.adb.device.ops

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.mcp.FakeToolContext
import spock.adb.mcp.text
import spock.adb.mcp.tools.SimulateProcessDeathTool
import spock.adb.mcp.tools.ToolSafety

/**
 * Process death has to be *observed*, not assumed: `am kill` exits 0 whether or not it killed
 * anything, and on an API 34 emulator a single kill 2.5 s after Home often left the process alive
 * while the tool window reported it dead.
 */
class SimulateProcessDeathTest {

    private val noPause: (Long) -> Unit = {}

    @Test
    fun `backgrounds the app, kills until the pid is gone, and resumes the task from the launcher`() {
        val script = ProcessDeathScript(killsNeeded = 3)
        val (device, commands) = scriptedDevice(script::reply)

        val death = AppOperations(device, pause = noPause).simulateProcessDeath(PACKAGE)

        assertEquals(setOf("100"), death.pidsBefore)
        assertEquals(setOf("200"), death.pidsAfter)
        assertEquals("$PACKAGE/.MainActivity", death.relaunched)
        assertEquals(3, commands.count { it.startsWith("am kill") }, "kept killing until the process was gone")
        assertTrue("input keyevent 3" in commands, "the app was on screen, so it was sent home first")
        assertTrue(
            commands.indexOf("input keyevent 3") < commands.indexOfFirst { it.startsWith("am kill") },
            "am kill only kills a background app",
        )
        // `am start -n` alone pushes a new launcher activity instead of restoring the task.
        assertTrue(
            commands.any {
                it.startsWith("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n")
            },
            "relaunched as the launcher icon does: $commands",
        )
        assertFalse(commands.any { it.startsWith("am force-stop") }, "a force-stop would drop saved state")
    }

    @Test
    fun `an app already in the background is not sent home again`() {
        val (device, commands) = scriptedDevice(ProcessDeathScript(foreground = false)::reply)

        AppOperations(device, pause = noPause).simulateProcessDeath(PACKAGE)

        assertFalse("input keyevent 3" in commands)
    }

    @Test
    fun `a process that will not die is reported and nothing is relaunched`() {
        val (device, commands) = scriptedDevice(ProcessDeathScript(killsNeeded = Int.MAX_VALUE)::reply)

        val failure = assertThrows<IllegalStateException> {
            AppOperations(device, pause = noPause).simulateProcessDeath(PACKAGE)
        }

        assertTrue("still running (pid 100)" in failure.message.orEmpty(), failure.message)
        assertEquals(AppOperations.KILL_ATTEMPTS, commands.count { it.startsWith("am kill") })
        assertFalse(commands.any { it.startsWith("am start") }, "relaunched an app that was never killed")
    }

    @Test
    fun `an app that is not running is refused before anything is sent to it`() {
        val (device, commands) = scriptedDevice(ProcessDeathScript(running = false)::reply)

        val failure = assertThrows<IllegalStateException> {
            AppOperations(device, pause = noPause).simulateProcessDeath(PACKAGE)
        }

        assertTrue("not running" in failure.message.orEmpty(), failure.message)
        assertFalse(commands.any { it.startsWith("am ") || it.startsWith("input ") }, "$commands")
    }

    @Test
    fun `an app that is not installed is refused`() {
        val (device, _) = scriptedDevice { "" }

        assertThrows<AppNotInstalledException> {
            AppOperations(device, pause = noPause).simulateProcessDeath(PACKAGE)
        }
    }

    @Test
    fun `without relaunch the process stays dead`() {
        val (device, commands) = scriptedDevice(ProcessDeathScript()::reply)

        val death = AppOperations(device, pause = noPause).simulateProcessDeath(PACKAGE, relaunch = false)

        assertNull(death.relaunched)
        assertTrue(death.pidsAfter.isEmpty())
        assertFalse(commands.any { it.startsWith("am start") })
    }

    @Test
    fun `the tool reports both pids and runs without a confirmation`() {
        val (device, _) = scriptedDevice(ProcessDeathScript()::reply)
        val context = FakeToolContext(
            available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)),
            applicationId = PACKAGE,
        )

        val tool = SimulateProcessDeathTool()
        val result = tool.execute(JsonObject(), context)

        assertEquals(ToolSafety.SAFE_ACTION, tool.safety)
        assertFalse(result.isError, result.text())
        assertTrue("pid 100 is gone" in result.text(), result.text())
        assertTrue("as pid 200" in result.text(), result.text())
        assertTrue(context.confirmations.isEmpty())
    }

    @Test
    fun `the tool turns a process that will not die into an error the agent can read`() {
        val (device, _) = scriptedDevice(ProcessDeathScript(running = false)::reply)
        val context = FakeToolContext(
            available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)),
            applicationId = PACKAGE,
        )

        val result = SimulateProcessDeathTool().execute(JsonObject(), context)

        assertTrue(result.isError)
        assertTrue("not running" in result.text(), result.text())
    }
}

/**
 * A device whose app is pid 100 until [killsNeeded] `am kill`s have landed, and pid 200 once
 * relaunched.
 */
internal class ProcessDeathScript(
    private val killsNeeded: Int = 1,
    private val foreground: Boolean = true,
    private val running: Boolean = true,
) {
    private var kills = 0
    private var relaunched = false

    fun reply(command: String): String = when {
        command.startsWith("pidof") -> when {
            relaunched -> "200"
            running && kills < killsNeeded -> "100"
            else -> ""
        }
        // Android 13+: no mResumedActivity, so the operation falls back to topResumedActivity.
        command.contains("grep mResumedActivity") -> ""
        command.contains("grep topResumedActivity") -> {
            val top = if (foreground) "$PACKAGE/.MainActivity" else "com.android.launcher/.Launcher"
            "    topResumedActivity=ActivityRecord{f285 u0 $top t113}"
        }
        command.startsWith("am kill") -> "".also { kills++ }
        command.startsWith("am start") -> "".also { relaunched = true }
        else -> healthyDevice(command)
    }
}
