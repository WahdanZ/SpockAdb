package spock.adb.logcat

import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Clearing the device buffer is a blocking shell command, and the panel runs it off the EDT.
 *
 * What can be checked here is the half that made that possible: the failure is *returned*
 * rather than swallowed into the IDE log, so the caller can say "the view is clear; the device
 * buffer is not" instead of the developer believing a clear that never happened. That the panel
 * calls it on a pooled thread is a threading property the unit harness cannot observe; it is
 * enforced by the doc contract on [LogcatStream.clearDeviceBuffer] and by review.
 */
class LogcatStreamTest {

    private fun device(): IDevice = mockk(relaxed = true) {
        every { serialNumber } returns "emulator-5554"
    }

    @Test
    fun `a successful clear reports no failure`() {
        val stream = LogcatStream(device(), onEntry = {})

        assertNull(stream.clearDeviceBuffer())
    }

    @Test
    fun `a failed clear hands the reason back instead of swallowing it`() {
        val broken = device()
        every {
            broken.executeShellCommand(any(), any(), any<Long>(), any())
        } throws IOException("device offline")

        val failure = LogcatStream(broken, onEntry = {}).clearDeviceBuffer()

        assertNotNull(failure)
        assertNotNull(failure!!.message)
    }
}
