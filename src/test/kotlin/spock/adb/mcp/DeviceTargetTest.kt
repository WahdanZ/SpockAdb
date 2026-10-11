package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.DeviceTarget
import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.confirmDestructive

class DeviceTargetTest {

    @Test
    fun `the Android target is the selected device`() {
        val context = FakeToolContext()
        val target = context.requireTarget()

        assertEquals(DeviceTarget.Android(context.requireDevice()), target)
        assertEquals(context.requireDevice().serialNumber, target.id)
    }

    @Test
    fun `a device-based confirmation reaches the implementation as an Android target`() {
        val context = FakeToolContext()
        val device = context.requireDevice()

        context.confirmDestructive("android_clear_app_data", "Clears data.", device)

        assertEquals(listOf<DeviceTarget>(DeviceTarget.Android(device)), context.confirmationTargets)
    }

    @Test
    fun `a device-based confirmation goes through a wrapper that overrides only the target overload`() {
        val base = FakeToolContext(confirmationAnswer = false)
        val wrapper = object : ToolContext by base {
            override fun confirmDestructive(toolName: String, summary: String, target: DeviceTarget): Boolean = true
        }

        assertTrue(wrapper.confirmDestructive("android_clear_app_data", "Clears data.", base.requireDevice()))
        assertEquals(emptyList<String>(), base.confirmations)
    }

    @Test
    fun `an iOS simulator is named by its name and runtime, and selected by its UDID`() {
        val simulator = DeviceTarget.IosSimulator("8AB66C76-3001-43E7-AAD8-0A53C185995E", "iPhone 15 Pro", "iOS 26.3")

        assertEquals("iPhone 15 Pro (iOS 26.3 simulator)", simulator.label)
        assertEquals("8AB66C76-3001-43E7-AAD8-0A53C185995E", simulator.id)
    }
}
