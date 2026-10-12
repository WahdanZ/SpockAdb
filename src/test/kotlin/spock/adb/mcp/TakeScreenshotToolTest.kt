package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.TakeScreenshotTool
import spock.adb.mcp.tools.ToolContent
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

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

    /** A real PNG for the scaling cases, which have to decode it. */
    private fun realPng(width: Int = 200, height: Int = 100): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, width / 2, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun capture(arguments: JsonObject.() -> Unit, png: ByteArray = realPng()) =
        TakeScreenshotTool().execute(
            JsonObject().apply(arguments),
            contextFor(deviceReplying(Base64.getEncoder().encodeToString(png)).first),
        )

    private fun decoded(image: ToolContent.Image) =
        ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.base64Data)))

    @Test
    fun `a scaled screenshot is smaller and says how to get back to screen pixels`() {
        val result = capture({ addProperty("scale", 0.5) })

        assertFalse(result.isError, result.text())
        val image = result.content.filterIsInstance<ToolContent.Image>().single()
        assertEquals("image/png", image.mimeType)
        assertEquals(100, decoded(image).width)
        assertEquals(50, decoded(image).height)
        assertEquals(
            "Screenshot scaled 0.5 (100x50 of 200x100) — multiply image coordinates by 2 for android_tap.",
            result.text(),
        )
    }

    @Test
    fun `jpeg is returned as jpeg`() {
        val result = capture({
            addProperty("scale", 0.25)
            addProperty("format", "JPEG")
        })

        val image = result.content.filterIsInstance<ToolContent.Image>().single()
        assertEquals("image/jpeg", image.mimeType)
        val bytes = Base64.getDecoder().decode(image.base64Data)
        // Every JPEG starts with the SOI marker.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(50, decoded(image).width)
        assertTrue(result.text().contains("multiply image coordinates by 4"), result.text())
    }

    @Test
    fun `full-size jpeg needs no coordinate note`() {
        val result = capture({ addProperty("format", "jpeg") })

        assertEquals("image/jpeg", (result.content.single() as ToolContent.Image).mimeType)
    }

    @Test
    fun `a scale or format it cannot use is refused before anything is captured`() {
        listOf(
            Triple("scale", 0.05, "Argument 'scale' must be between 0.1 and 1.0, got 0.05."),
            Triple("scale", 1.5, "Argument 'scale' must be between 0.1 and 1.0, got 1.5."),
            Triple("format", "gif", "Unknown format 'gif'. Use one of: png, jpeg."),
        ).forEach { (name, value, expected) ->
            val device = mockk<IDevice>(relaxed = true)
            val arguments = JsonObject().apply {
                if (value is Number) addProperty(name, value) else addProperty(name, value.toString())
            }

            val result = TakeScreenshotTool().execute(
                arguments,
                contextFor(FakeToolContext.device("emulator-5554").copy(device = device)),
            )

            assertTrue(result.isError, expected)
            assertEquals(expected, result.text())
            verify(exactly = 0) { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) }
        }
    }

    @Test
    fun `a scale sent as a string is refused rather than parsed`() {
        val result = capture({ addProperty("scale", "0.5") })

        assertTrue(result.isError)
        assertEquals("Argument 'scale' must be a number, got \"0.5\".", result.text())
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
