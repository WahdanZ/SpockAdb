package spock.adb.device.ops

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import spock.adb.command.ClearAppCacheCommand
import spock.adb.command.ClearAppDataCommand
import spock.adb.command.ForceKillAppCommand
import spock.adb.command.ProcessDeathCommand
import spock.adb.command.RestartAppCommand
import spock.adb.command.UninstallAppCommand
import spock.adb.mcp.FakeToolContext
import spock.adb.mcp.text
import spock.adb.mcp.tools.ToolRegistry

/**
 * The tool window and an agent must do the same thing to the device for the same action.
 *
 * Each case runs the IDE path and the agent path against identically scripted devices and
 * compares what each one actually sent. That is the only claim worth making: two call sites
 * that share a class can still diverge by checking a precondition on one side and not the
 * other, which is exactly how `android_stop_app` came to force-stop a package the tool
 * window would have refused to touch.
 *
 * A failure here means one entry path changed and the other did not.
 */
class AppOperationsParityTest {

    private val project = mockk<Project>(relaxed = true)

    @Test
    fun `launch sends what the shared operation sends and nothing more`() {
        assertSameDeviceTraffic("android_launch_app") { device -> AppOperations(device).launch(PACKAGE) }
    }

    @Test
    fun `stop is the same from the tool window and from an agent`() {
        assertSameDeviceTraffic("android_stop_app") { device ->
            ForceKillAppCommand().execute(PACKAGE, project, device)
        }
    }

    @Test
    fun `restart is the same from the tool window and from an agent`() {
        assertSameDeviceTraffic("android_restart_app") { device ->
            RestartAppCommand().execute(PACKAGE, project, device)
        }
    }

    @Test
    fun `process death is the same from the tool window and from an agent`() {
        assertSameDeviceTraffic("android_simulate_process_death", { ProcessDeathScript()::reply }) { device ->
            ProcessDeathCommand().execute(PACKAGE, project, device)
        }
    }

    @Test
    fun `clear data is the same from the tool window and from an agent`() {
        assertSameDeviceTraffic("android_clear_app_data") { device ->
            ClearAppDataCommand().execute(PACKAGE, project, device)
        }
    }

    @Test
    fun `clear cache is the same from the tool window and from an agent`() {
        assertSameDeviceTraffic("android_clear_app_cache") { device ->
            ClearAppCacheCommand().execute(PACKAGE, project, device)
        }
    }

    @Test
    fun `uninstall is the same from the tool window and from an agent`() {
        val (ide, agent) = assertSameDeviceTraffic("android_uninstall_app") { device ->
            UninstallAppCommand().execute(PACKAGE, project, device)
        }

        // The uninstall itself is not a shell command, so the traffic comparison cannot see it.
        verify(exactly = 1) { ide.uninstallPackage(PACKAGE) }
        verify(exactly = 1) { agent.uninstallPackage(PACKAGE) }
    }

    /**
     * Runs [ide] and the tool called [toolName] against equally scripted devices and asserts
     * they sent the same shell commands, in the same order.
     *
     * The agent path is given an approving developer: a destructive tool that was declined
     * would send nothing and pass this by doing nothing at all.
     *
     * @param script makes each device's replies; called once per device, so a stateful script
     *   starts fresh for each path.
     * @return the two devices, for a caller that has more to check than the shell traffic.
     */
    private fun assertSameDeviceTraffic(
        toolName: String,
        script: () -> (String) -> String = { ::healthyDevice },
        ide: (IDevice) -> Unit,
    ): Pair<IDevice, IDevice> {
        val (ideDevice, ideCommands) = scriptedDevice(script())
        ide(ideDevice)

        val (agentDevice, agentCommands) = scriptedDevice(script())
        val context = FakeToolContext(
            available = listOf(FakeToolContext.device("emulator-5554").copy(device = agentDevice)),
            applicationId = PACKAGE,
            confirmationAnswer = true,
        )
        val result = ToolRegistry.find(toolName)!!.execute(JsonObject(), context)

        assertFalse(result.isError, "$toolName failed on a healthy device: ${result.text()}")
        assertEquals(
            ideCommands,
            agentCommands,
            "$toolName and the tool window no longer do the same thing to the device",
        )
        return ideDevice to agentDevice
    }
}
