package spock.adb.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceInfoTest {

    private fun info(
        serial: String = "39021FDJH00123",
        model: String = "Pixel 7",
        manufacturer: String = "Google",
        androidVersion: String = "14",
        apiLevel: Int? = 34,
        abi: String = "arm64-v8a",
        isEmulator: Boolean = false,
        state: DeviceState = DeviceState.ONLINE,
    ) = DeviceInfo(serial, model, manufacturer, androidVersion, apiLevel, abi, isEmulator, state)

    @Test
    fun `labels a physical device with version and architecture`() {
        assertEquals("Device: Google Pixel 7 - Android 14 (API 34) - arm64-v8a", info().label())
    }

    @Test
    fun `labels an emulator distinctly`() {
        val label = info(model = "sdk_gphone64_arm64", manufacturer = "Google", isEmulator = true).label()

        assertTrue(label.startsWith("Emulator: "), label)
    }

    @Test
    fun `does not repeat the manufacturer when the model already carries it`() {
        assertEquals("Samsung SM-G991B", info(model = "SM-G991B", manufacturer = "Samsung").displayName)
        assertEquals("Google Pixel 7", info(model = "Pixel 7", manufacturer = "Google").displayName)
        // A model that already begins with the manufacturer is not prefixed again.
        assertEquals("Google Pixel", info(model = "Google Pixel", manufacturer = "Google").displayName)
    }

    @Test
    fun `appends the state only when the device cannot accept commands`() {
        assertFalse(info().label().contains("online"))
        assertTrue(info(state = DeviceState.UNAUTHORIZED).label().endsWith("unauthorized"))
        assertTrue(info(state = DeviceState.OFFLINE).label().endsWith("offline"))
    }

    @Test
    fun `only an online device is usable`() {
        assertTrue(info(state = DeviceState.ONLINE).isUsable)
        listOf(DeviceState.OFFLINE, DeviceState.UNAUTHORIZED, DeviceState.BOOTLOADER, DeviceState.UNKNOWN)
            .forEach { assertFalse(info(state = it).isUsable, it.name) }
    }

    @Test
    fun `falls back to the serial when the device reported no model`() {
        val unknown = DeviceInfo.unknown("emulator-5554")

        assertEquals("emulator-5554", unknown.displayName)
        assertEquals("emulator-5554", unknown.describe())
    }

    @Test
    fun `omits version and architecture the device did not report`() {
        assertEquals("Device: Google Pixel 7", info(androidVersion = "", apiLevel = null, abi = "").label())
        assertEquals("", info(androidVersion = "", apiLevel = null).androidVersionLabel())
        assertEquals("API 34", info(androidVersion = "").androidVersionLabel())
        assertEquals("Android 14", info(apiLevel = null).androidVersionLabel())
    }

    @Test
    fun `describe pairs the name with the serial so prompts are unambiguous`() {
        assertEquals("Google Pixel 7 (39021FDJH00123)", info().describe())
    }
}
