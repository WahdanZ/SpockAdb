package spock.adb.device.ops

import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The shared app operations, tested against a scripted device — no IDE, no agent, no ADB.
 *
 * Several of these pin behaviour that only one of the two entry paths had before the
 * operations existed, which is the whole point: the path that was missing it was missing it
 * by accident.
 */
class AppOperationsTest {

    @Test
    fun `launching resolves the launcher activity and starts it`() {
        val (device, commands) = scriptedDevice()

        val activity = AppOperations(device).launch(PACKAGE)

        assertEquals("$PACKAGE/.MainActivity", activity)
        assertTrue(commands.any { it.startsWith("am start -n") }, commands.toString())
    }

    @Test
    fun `an app with no launchable activity is refused by name`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.startsWith("cmd package resolve-activity")) "" else healthyDevice(command)
        }

        val thrown = assertThrows<IllegalStateException> { AppOperations(device).launch(PACKAGE) }

        assertTrue(thrown.message!!.contains(PACKAGE), thrown.message)
        assertTrue(commands.none { it.startsWith("am start") }, "nothing may be started: $commands")
    }

    @Test
    fun `stopping a package that is not installed sends no force-stop`() {
        // android_stop_app force-stopped whatever it was given, while the tool window's
        // Force Kill checked first. One of the two was wrong and it was not the button.
        val (device, commands) = scriptedDevice { "" }

        assertThrows<AppNotInstalledException> { AppOperations(device).stop(PACKAGE) }

        assertTrue(commands.none { it.startsWith("am force-stop") }, commands.toString())
    }

    @Test
    fun `restarting stops the app before it starts it again`() {
        val (device, commands) = scriptedDevice()

        AppOperations(device).restart(PACKAGE)

        val stopped = commands.indexOfFirst { it.startsWith("am force-stop") }
        val started = commands.indexOfFirst { it.startsWith("am start -n") }
        assertTrue(stopped in 0 until started, "expected a force-stop before the start: $commands")
    }

    @Test
    fun `clearing data and restarting wipes before it launches`() {
        val (device, commands) = scriptedDevice()

        AppOperations(device).clearDataAndRestart(PACKAGE)

        val cleared = commands.indexOfFirst { it.startsWith("pm clear") }
        val started = commands.indexOfFirst { it.startsWith("am start -n") }
        assertTrue(cleared in 0 until started, "expected a clear before the start: $commands")
    }

    @Test
    fun `an uninstall the device refuses is a failure, not a success`() {
        // The tool window discarded this and reported every uninstall as done.
        val (device, _) = scriptedDevice()
        every { device.uninstallPackage(PACKAGE) } returns "DELETE_FAILED_DEVICE_POLICY_MANAGER"

        val thrown = assertThrows<IllegalStateException> { AppOperations(device).uninstall(PACKAGE) }

        assertTrue(thrown.message!!.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER"), thrown.message)
    }

    @Test
    fun `an uninstall the device accepts removes the package`() {
        val (device, _) = scriptedDevice()
        every { device.uninstallPackage(PACKAGE) } returns null

        AppOperations(device).uninstall(PACKAGE)

        verify(exactly = 1) { device.uninstallPackage(PACKAGE) }
    }

    @Test
    fun `one action asks the device once whether the app is installed`() {
        // A destructive caller checks before it puts a dialog in front of the developer, and
        // the operation it then runs checks too. That pair is one question, not two.
        val (device, commands) = scriptedDevice()
        val operations = AppOperations(device)

        assertTrue(operations.isInstalled(PACKAGE))
        operations.clearData(PACKAGE)

        assertEquals(1, commands.count { it.startsWith("pm list packages") }, commands.toString())
    }

    @Test
    fun `clearing the cache goes through run-as and not pm clear`() {
        val (device, commands) = scriptedDevice()

        AppOperations(device).clearCache(PACKAGE)

        assertTrue(commands.any { it.startsWith("run-as") }, commands.toString())
        assertTrue(commands.none { it.startsWith("pm clear") }, "a cache clear may never wipe data: $commands")
    }
}
