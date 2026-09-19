package spock.adb

import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.Network
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Whether the Wi-Fi and mobile data buttons can be pressed at all.
 *
 * The row first took the button's enabled state from the read that fills its label in, so every
 * path where that read did not land — attached before a device was published, a read retired by
 * a newer one that then returned early, a controller that never answered — left a button that
 * looked ordinary and did nothing. From the outside that is indistinguishable from a toggle
 * that runs and fails, which is what made it hard to see: the device never heard about it.
 */
class NetworkToggleRowTest {

    private val device = ConnectedDevice(mockk<IDevice>(relaxed = true), DeviceInfo.unknown("emulator-5554"))

    private fun JPanel.toggle(): JButton = components.filterIsInstance<JButton>().single()

    @Test
    fun `a read that never answers does not leave a dead button`() {
        // relaxed: networkState accepts the callback and never calls it, as a device that has
        // gone away does.
        val controller = mockk<AdbController>(relaxed = true)
        val row = NetworkToggleRow(Network.WIFI, "Wi-Fi")

        row.attach(controller) { device }

        assertTrue(row.toggle().isEnabled, "a device is selected, so the button has to be pressable")
    }

    @Test
    fun `with no device there is nothing to press`() {
        val row = NetworkToggleRow(Network.WIFI, "Wi-Fi")
        row.attach(mockk(relaxed = true)) { null }

        assertFalse(row.toggle().isEnabled)
    }

    @Test
    fun `pressing it reaches the device`() {
        val controller = mockk<AdbController>(relaxed = true)
        val row = NetworkToggleRow(Network.MOBILE, "Mobile data")
        row.attach(controller) { device }

        row.toggle().doClick()

        verify { controller.toggleNetwork(device.device, Network.MOBILE, any()) }
    }

    @Test
    fun `the button waits for the toggle it started, and only for that`() {
        val done = slot<() -> Unit>()
        val controller = mockk<AdbController>(relaxed = true)
        every { controller.toggleNetwork(any(), any(), capture(done)) } answers {}
        val row = NetworkToggleRow(Network.WIFI, "Wi-Fi")
        row.attach(controller) { device }

        row.toggle().doClick()
        assertFalse(row.toggle().isEnabled, "a toggle is on its way; a second press would race it")

        done.captured()
        assertTrue(row.toggle().isEnabled, "once the device has answered the button is live again")
    }

    @Test
    fun `a refresh never disables it`() {
        val controller = mockk<AdbController>(relaxed = true)
        val row = NetworkToggleRow(Network.WIFI, "Wi-Fi")
        row.attach(controller) { device }

        repeat(REFRESHES) { row.refresh() }

        assertTrue(row.toggle().isEnabled, "reads fill the label in; they do not decide the button")
    }

    private companion object {
        const val REFRESHES = 5
    }
}
