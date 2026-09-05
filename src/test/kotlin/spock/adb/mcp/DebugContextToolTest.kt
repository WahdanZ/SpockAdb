package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.DebugContextTool
import spock.adb.mcp.tools.ToolContent
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The triage bundle is the tool an AI assistant reaches for first, so what matters is that it
 * stays honest under partial failure and stays bounded under a large screen. Both are tested
 * here against a routed fake shell rather than a device.
 */
class DebugContextToolTest {

    private val uiDump = javaClass.getResource("/uidumps/compose-material3.xml")!!.readText()

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x01, 0x02, 0x03,
    )

    private val issued = mutableListOf<String>()

    /**
     * A device whose shell answers each command differently, the way a real one does. A single
     * canned reply would let a bug that sends the wrong command to the wrong section pass.
     */
    private fun routedDevice(
        dumpReply: String = "UI hierarchy dumped to: /sdcard/spock-adb-ui-dump.xml",
        logcatReply: String = "01-01 00:00:00.000  1234  1234 E MyApp: boom",
    ): ConnectedDevice {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val issuedCommand = command.captured
            issued += issuedCommand
            val reply = when {
                issuedCommand.startsWith("uiautomator dump") -> dumpReply
                issuedCommand.startsWith("cat ") -> uiDump
                issuedCommand.startsWith("pidof") -> "1234"
                issuedCommand.startsWith("logcat") -> logcatReply
                issuedCommand.startsWith("screencap") ->
                    Base64.getEncoder().encodeToString(pngBytes)
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return FakeToolContext.device("emulator-5554").copy(device = device)
    }

    private fun run(arguments: JsonObject = JsonObject(), device: ConnectedDevice = routedDevice()) =
        DebugContextTool().execute(arguments, FakeToolContext(available = listOf(device)))

    private fun textOf(result: spock.adb.mcp.tools.ToolResult) =
        result.content.filterIsInstance<ToolContent.Text>().single().text

    private fun include(vararg sections: String) = JsonObject().apply {
        add("include", JsonArray().also { array -> sections.forEach(array::add) })
    }

    @Test
    fun `by default it captures activity, ui and logcat but not the screenshot`() {
        val result = run()
        val text = textOf(result)

        assertFalse(result.isError)
        assertTrue(text.contains("## Current activity"), text)
        assertTrue(text.contains("## UI semantics tree"), text)
        assertTrue(text.contains("## Recent logcat"), text)
        assertFalse(text.contains("## Screenshot"), "screenshot must be opt-in: $text")
        assertTrue(
            result.content.none { it is ToolContent.Image },
            "no image should be attached unless asked for",
        )
    }

    @Test
    fun `include selects only the sections asked for`() {
        val text = textOf(run(include("logcat")))

        assertTrue(text.contains("## Recent logcat"), text)
        assertFalse(text.contains("## UI semantics tree"), text)
        assertFalse(text.contains("## Current activity"), text)
    }

    @Test
    fun `the screenshot section attaches a real image alongside the text`() {
        val result = run(include("screenshot"))

        val image = result.content.filterIsInstance<ToolContent.Image>().single()
        assertTrue(
            Base64.getDecoder().decode(image.base64Data).contentEquals(pngBytes),
            "the attached image must be the bytes the device returned",
        )
        assertTrue(textOf(result).contains("Attached as an image"), textOf(result))
    }

    @Test
    fun `the UI section always states the framework, so Compose test tags are never assumed`() {
        // A Compose screen without testTagsAsResourceId is the single most common reason an
        // agent's selectors silently match nothing. It must be told, every time.
        val text = textOf(run(include("ui")))

        assertTrue(text.contains("UI framework:"), text)
    }

    @Test
    fun `the UI tree is bounded by maxUiDepth and says how much it hid`() {
        val arguments = include("ui").apply { addProperty("maxUiDepth", 1) }

        val text = textOf(run(arguments))

        assertTrue(text.contains("hidden by maxUiDepth=1"), text)
        assertTrue(text.contains("more node(s) below"), "hidden nodes must be counted, not dropped: $text")
    }

    @Test
    fun `logcat lines are capped however many the caller asks for`() {
        run(include("logcat").apply { addProperty("maxLogcatLines", 999_999) })

        val logcat = issued.single { it.startsWith("logcat") }
        assertTrue(logcat.contains("-t 2000"), "should clamp to the documented cap: $logcat")
    }

    @Test
    fun `logcat is filtered by pid rather than by matching text`() {
        run(include("logcat"))

        val logcat = issued.single { it.startsWith("logcat") }
        assertTrue(logcat.contains("--pid=1234"), logcat)
    }

    @Test
    fun `a section that fails does not cost the caller the sections that worked`() {
        // The whole point of the bundle: a screen that refuses to dump must not also hide the
        // crash sitting in logcat next to it.
        val device = routedDevice(dumpReply = "ERROR: could not get idle state.")

        val text = textOf(run(include("ui", "logcat"), device))

        assertTrue(text.contains("Could not capture this section"), text)
        assertTrue(text.contains("MyApp: boom"), "logcat must survive a failed UI dump: $text")
    }

    @Test
    fun `an unknown section name is ignored rather than fatal`() {
        // A newer client asking for a section this build does not have should still get the
        // rest, not an error.
        val text = textOf(run(include("logcat", "telemetry")))

        assertTrue(text.contains("## Recent logcat"), text)
    }

    @Test
    fun `asking for nothing recognisable is an error that lists what is available`() {
        val result = run(include("telemetry"))

        assertTrue(result.isError)
        assertTrue(textOf(result).contains("screenshot"), textOf(result))
    }

    @Test
    fun `the report names the device it describes`() {
        assertTrue(textOf(run()).contains("emulator-5554"), textOf(run()))
    }
}
