package spock.adb.context

import com.android.ddmlib.IDevice
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState

/** What the status bar says about the device and app every Spock surface acts on. */
class ContextTextTest {

    private val pixel = ConnectedDevice(
        mockk<IDevice>(relaxed = true),
        DeviceInfo("R58M", "Pixel 8", "Google", "15", 35, "arm64-v8a", isEmulator = false, state = DeviceState.ONLINE),
    )
    private val emulator = ConnectedDevice(
        mockk<IDevice>(relaxed = true),
        DeviceInfo(
            "emulator-5554",
            "sdk_gphone64_arm64",
            "Google",
            "14",
            34,
            "arm64-v8a",
            isEmulator = true,
            state = DeviceState.ONLINE,
        ),
    )

    @Test
    fun `device and app, in the fewest words`() {
        val snapshot = SpockSelection.Snapshot(devices = listOf(pixel), device = pixel, app = "spock.adb.sample")
        assertEquals("Pixel 8 · API 35 · spock.adb.sample", ContextText.of(snapshot))
    }

    @Test
    fun `an emulator is named by its port, not its system image`() {
        val snapshot = SpockSelection.Snapshot(devices = listOf(emulator), device = emulator)
        assertEquals("Emulator 5554 · API 34", ContextText.of(snapshot))
    }

    @Test
    fun `with nothing connected it says so`() {
        assertEquals(ContextText.NO_DEVICE, ContextText.of(SpockSelection.Snapshot()))
    }

    @Test
    fun `the tooltip says whether Android Studio's choice is followed`() {
        val snapshot = SpockSelection.Snapshot(devices = listOf(pixel), device = pixel, app = "a.b")
        assertTrue(ContextText.tooltip(snapshot, followsStudio = true).contains("Follows Android Studio"))
        assertTrue(ContextText.tooltip(snapshot, followsStudio = false).contains("Chosen here only"))
    }
}
