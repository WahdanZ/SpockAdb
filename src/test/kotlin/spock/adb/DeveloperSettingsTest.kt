package spock.adb

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class DeveloperSettingsTest {

    @Test
    fun `without a device every setting is unknown, and nothing is asked`() {
        val settings = DeveloperSettings.read(null)

        assertNull(settings.dontKeep)
        assertNull(settings.window)
    }

    @Test
    fun `a shell the device refuses comes back as a failure the section can catch`() {
        // What an emulator whose adbd has run out of file descriptors does to every shell.
        val device = mockk<IDevice>(relaxed = true)
        every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } throws
            mockk<AdbCommandRejectedException>(relaxed = true)

        val read = runCatching { DeveloperSettings.read(device) }

        assertTrue(read.isFailure, "the refusal must surface as a failure, not as settings read as Off")
    }
}
