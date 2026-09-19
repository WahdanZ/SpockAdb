package spock.adb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState

class DestructiveActionConfirmationTest {

    private val device = DeviceInfo(
        serialNumber = "emulator-5554",
        model = "Pixel 7",
        manufacturer = "Google",
        androidVersion = "14",
        apiLevel = 34,
        abi = "arm64-v8a",
        isEmulator = true,
        state = DeviceState.ONLINE,
    )

    @Test
    fun `an app storage write offers undo`() {
        val message = DestructiveActionConfirmation.appStorageWriteMessage(
            device,
            "com.example.app",
            "Apply 1 change to shared_prefs/settings.xml",
            restart = false,
            undoable = true,
        )

        assertTrue(message.contains("Undo Last Apply can restore the previous file."), message)
    }

    @Test
    fun `confirming the undo itself does not promise another undo`() {
        val message = DestructiveActionConfirmation.appStorageWriteMessage(
            device,
            "com.example.app",
            "Restore shared_prefs/settings.xml to what it held before the last apply",
            restart = true,
            undoable = false,
        )

        assertFalse(message.contains("Undo"), message)
        assertTrue(message.endsWith("and is started again afterwards."), message)
    }
}
