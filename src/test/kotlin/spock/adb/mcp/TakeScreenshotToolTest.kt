package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.TakeScreenshotTool
import spock.adb.mcp.tools.ToolContent
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Screenshots must not go through IDevice.getScreenshot(): Android Studio ships that as a
 * stub which fails with "This method is not used in Android Studio", so the tool captures
 * through the shell instead. These tests pin that, and pin the base64 transport that keeps
 * the bytes intact across ddmlib's text-only shell channel.
 */
class TakeScreenshotToolTest {

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x01, 0x02, 0x03,
    )

    /** A device whose shell replies with [reply] for any command, recording the command. */
    private fun deviceReplying(reply: String): Pair<ConnectedDevice, () -> String> {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        val connected = FakeToolContext.device("emulator-5554").copy(device = device)
        return connected to { command.captured }
    }

    private fun contextFor(connected: ConnectedDevice) =
        FakeToolContext(available = listOf(connected))

    @Test
    fun `captures through the shell and returns the PNG unchanged`() {
        // The device wraps base64 at 76 columns, so the reply is deliberately split.
        val encoded = Base64.getEncoder().encodeToString(pngBytes)
        val (device, lastCommand) = deviceReplying(encoded.chunked(4).joinToString("\n") + "\n")

        val result = TakeScreenshotTool().execute(JsonObject(), contextFor(device))

        assertFalse(result.isError, "capture should succeed")
        assertEquals("screencap -p | base64", lastCommand())
        val image = result.content.single() as ToolContent.Image
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(pngBytes, Base64.getDecoder().decode(image.base64Data))
    }

    @Test
    fun `reports what the device said when the command fails`() {
        val (device, _) = deviceReplying("screencap: permission denied")

        val result = TakeScreenshotTool().execute(JsonObject(), contextFor(device))

        assertTrue(result.isError, "a failed capture must be an error result")
        val message = (result.content.single() as ToolContent.Text).text
        assertTrue(message.contains("permission denied"), "should quote the device: $message")
    }

    @Test
    fun `rejects output that decodes but is not a PNG`() {
        val notAPng = Base64.getEncoder().encodeToString("<html>captive portal</html>".toByteArray())
        val (device, _) = deviceReplying(notAPng)

        val result = TakeScreenshotTool().execute(JsonObject(), contextFor(device))

        assertTrue(result.isError, "non-PNG bytes must not be returned as an image")
        val message = (result.content.single() as ToolContent.Text).text
        assertTrue(message.contains("FLAG_SECURE"), "should name the likely cause: $message")
    }
}
