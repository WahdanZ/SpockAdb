package spock.adb.flutter.vmservice

import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.ShellCommandUnresponsiveException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.android.ddmlib.TimeoutException as AdbTimeoutException

class LogcatDiscoveryTest {

    private val announcement =
        "10-01 10:00:01.500  5021  5045 I flutter : The Dart VM service is listening on " +
            "http://127.0.0.1:43181/AbCdEfGh123=/"

    /** A device that answers `pidof` with 5021, and fails `logcat` with [logcatFailure] when given. */
    private fun device(logcatFailure: Exception? = null, pidofFailure: Exception? = null): IDevice {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every { device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>()) } answers {
            val reply = when {
                command.captured.startsWith("pidof") -> pidofFailure?.let { throw it } ?: "5021"
                command.captured.startsWith("logcat") -> logcatFailure?.let { throw it } ?: announcement
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return device
    }

    @Test
    fun `the announced address becomes a candidate tried after DDS ones, that forwards its port`() {
        val device = device()
        val candidate = LogcatDiscovery(device, "com.example.app").discover().single()

        val uri = candidate.open()
        candidate.release()

        assertFalse(candidate.ddsLikely)
        assertEquals(VmServiceSource.LOGCAT, candidate.source)
        verify { device.createForward(uri.port, 43_181) }
        verify { device.removeForward(uri.port) }
    }

    @Test
    fun `every way adb fails comes back as a VM Service exception`() {
        listOf(
            ShellCommandUnresponsiveException(),
            AdbTimeoutException(),
            AdbCommandRejectedException::class.java.getDeclaredConstructor(String::class.java)
                .apply { isAccessible = true }.newInstance("device offline"),
            IOException("connection reset"),
        ).forEach { failure ->
            val logcat = assertThrows<VmServiceException>(failure.javaClass.simpleName) {
                LogcatDiscovery(device(logcatFailure = failure), "com.example.app").discover()
            }
            assertTrue(logcat.message!!.startsWith("Could not read logcat"), logcat.message)
            val pidof = assertThrows<VmServiceException>(failure.javaClass.simpleName) {
                LogcatDiscovery(device(pidofFailure = failure), "com.example.app").discover()
            }
            assertTrue(pidof.message!!.contains("processes of com.example.app"), pidof.message)
        }
    }

    @Test
    fun `a forward adb refuses is a VM Service exception, and leaves nothing to release`() {
        val device = device()
        every { device.createForward(any<Int>(), any<Int>()) } throws AdbTimeoutException()
        val candidate = LogcatDiscovery(device, "com.example.app").discover().single()

        assertThrows<VmServiceException> { candidate.open() }
        candidate.release()

        verify(exactly = 0) { device.removeForward(any<Int>()) }
    }
}
