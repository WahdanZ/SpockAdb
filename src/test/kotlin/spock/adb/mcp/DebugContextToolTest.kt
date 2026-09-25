package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
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
        resumedReply: String = "  mResumedActivity: ActivityRecord{9f1c u0 com.example.app/.CheckoutActivity t12}",
        pidReply: String = "1234",
        screencapReply: String = Base64.getEncoder().encodeToString(pngBytes),
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
                issuedCommand.startsWith("pidof") -> pidReply
                issuedCommand.startsWith("dumpsys activity activities") -> resumedReply
                issuedCommand.startsWith("logcat") -> logcatReply
                issuedCommand.startsWith("screencap") -> screencapReply
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return FakeToolContext.device("emulator-5554").copy(device = device)
    }

    private fun run(arguments: JsonObject = full(), device: ConnectedDevice = routedDevice()) =
        DebugContextTool().execute(arguments, FakeToolContext(available = listOf(device)))

    private fun textOf(result: spock.adb.mcp.tools.ToolResult) =
        result.content.filterIsInstance<ToolContent.Text>().single().text

    /** The 4.x text bundle, which these first tests pin so its clients keep working. */
    private fun full() = JsonObject().apply { addProperty("format", "full") }

    private fun include(vararg sections: String) = full().apply {
        add("include", JsonArray().also { array -> sections.forEach(array::add) })
    }

    private fun summaryOf(arguments: JsonObject = JsonObject(), device: ConnectedDevice = routedDevice()): JsonObject {
        val result = run(arguments, device)
        assertFalse(result.isError, textOf(result))
        return JsonParser.parseString(textOf(result)).asJsonObject
    }

    private fun summaryIncluding(vararg sections: String) = JsonObject().apply {
        add("include", JsonArray().also { array -> sections.forEach(array::add) })
    }

    @Test
    fun `the full format by default captures activity, ui and logcat but not the screenshot`() {
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

    // --- The summary: the default since schema version 2 ---

    @Test
    fun `the default is a JSON summary with problems first and no raw data`() {
        val result = run(JsonObject())
        val text = textOf(result)
        val report = JsonParser.parseString(text).asJsonObject

        assertEquals(2, report["schemaVersion"].asInt)
        assertEquals(
            listOf("schemaVersion", "device", "packageName", "likelyProblems"),
            report.keySet().take(4),
            "problems must come before the sections that explain them",
        )
        assertFalse(text.contains("## "), "no 4.x headings in the summary: $text")
        assertFalse(text.contains("android.widget.") || text.contains("[0,0]"), "no raw UI tree: $text")
        assertFalse(text.contains("01-01 00:00:00.000  1234"), "no raw logcat lines: $text")
        assertTrue(result.content.none { it is ToolContent.Image })
    }

    @Test
    fun `the summary names the screen and says whether the app owns it`() {
        val screen = summaryOf()["screen"].asJsonObject

        assertEquals("CheckoutActivity", screen["activity"].asString)
        assertEquals("com.example.app/com.example.app.CheckoutActivity", screen["component"].asString)
        assertTrue(screen["appInForeground"].asBoolean)
    }

    @Test
    fun `another app in front is a problem, not a silent mismatch`() {
        val device = routedDevice(
            resumedReply = "  topResumedActivity=ActivityRecord{1 u0 com.android.launcher/.Home t1}"
        )

        val problems = summaryOf(summaryIncluding("screen"), device)["likelyProblems"].asJsonArray

        assertTrue(problems.any { it.asJsonObject["summary"].asString.contains("not in the foreground") }, "$problems")
    }

    @Test
    fun `an error in the app's log becomes a likely problem with its section`() {
        val problem = summaryOf(summaryIncluding("logs"))["likelyProblems"].asJsonArray.first().asJsonObject

        assertEquals("error", problem["severity"].asString)
        assertEquals("MyApp: boom", problem["summary"].asString)
        assertEquals("logs", problem["section"].asString)
    }

    @Test
    fun `a stopped app is reported, and its log is still read for the crash that stopped it`() {
        val crash = listOf(
            "01-01 00:00:01.000  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main",
            "01-01 00:00:01.000  4321  4321 E AndroidRuntime: Process: com.example.app, PID: 4321",
            "01-01 00:00:01.000  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: boom",
            "01-01 00:00:01.000  4321  4321 E AndroidRuntime: \tat com.example.app.Main.onCreate(Main.kt:1)",
        ).joinToString("\n")
        val report = summaryOf(summaryIncluding("app", "logs"), routedDevice(logcatReply = crash, pidReply = ""))

        assertFalse(report["app"].asJsonObject["running"].asBoolean)
        val first = report["likelyProblems"].asJsonArray.first().asJsonObject
        assertEquals("crash", first["type"].asString, "the crash outranks 'not running': $report")
    }

    @Test
    fun `the summary scans logcat for warnings and up across the whole device`() {
        summaryOf(summaryIncluding("logs"))

        val logcat = issued.single { it.startsWith("logcat") }
        assertTrue(logcat.contains("*:W"), logcat)
        assertFalse(logcat.contains("--pid"), "a crashed process has no pid to filter by: $logcat")
        assertTrue(logcat.contains("-t 1500"), logcat)
    }

    @Test
    fun `the UI is summarised, not rendered`() {
        val ui = summaryOf(summaryIncluding("ui"))["ui"].asJsonObject

        assertTrue(ui["framework"].asString.isNotBlank())
        assertTrue(ui["visibleNodes"].asInt > 0, "$ui")
        assertTrue(ui.has("accessibility"), "$ui")
    }

    @Test
    fun `every section names the call that returns its raw data`() {
        val report = summaryOf()
        val more = report["more"].asJsonObject

        assertEquals("android_get_logcat", more["logs"].asJsonObject["tool"].asString)
        assertEquals("com.example.app", more["logs"].asJsonObject["arguments"].asJsonObject["packageName"].asString)
        assertEquals("android_get_ui_tree", more["ui"].asJsonObject["tool"].asString)
    }

    @Test
    fun `a failing section is reported in sectionErrors and the rest still come back`() {
        val report = summaryOf(
            summaryIncluding("ui", "logs"),
            routedDevice(dumpReply = "ERROR: could not get idle state.")
        )

        assertTrue(report["sectionErrors"].asJsonObject.has("ui"), "$report")
        assertTrue(report.has("logs"), "$report")
    }

    @Test
    fun `4_x section names still work, summarised`() {
        val report = summaryOf(summaryIncluding("activity", "logcat"))

        assertTrue(report.has("screen"), "$report")
        assertTrue(report.has("logs"), "$report")
        assertFalse(report.has("ui"), "$report")
    }

    @Test
    fun `the screenshot is opt-in in the summary too`() {
        val result = run(summaryIncluding("screenshot"))

        assertTrue(result.content.any { it is ToolContent.Image })
        assertEquals("attached", JsonParser.parseString(textOf(result)).asJsonObject["screenshot"].asString)
    }

    @Test
    fun `an unknown format is an error that names both`() {
        val result = run(JsonObject().apply { addProperty("format", "xml") })

        assertTrue(result.isError)
        assertTrue(textOf(result).contains("summary") && textOf(result).contains("full"), textOf(result))
    }

    @Test
    fun `the summary stays bounded however noisy the log is`() {
        val noisy = (1..2_000).joinToString("\n") { index ->
            "01-01 00:00:00.000  1234  1234 E Tag$index: distinct failure number $index " + "x".repeat(300)
        }

        val text = textOf(run(JsonObject(), routedDevice(logcatReply = noisy)))

        assertTrue(text.length <= 12_000, "summary was ${text.length} chars")
        assertTrue(JsonParser.parseString(text).asJsonObject["moreProblems"].asInt > 0)
    }

    @Test
    fun `a failed screenshot's message cannot push the summary past its bound`() {
        // The note is added after the collector's size cut, so it has to be bounded itself.
        val device = routedDevice(screencapReply = "screencap: " + "not base64 ".repeat(5_000))

        val result = run(summaryIncluding("screenshot"), device)
        val text = textOf(result)

        assertTrue(result.content.none { it is ToolContent.Image })
        assertTrue(text.length <= 12_000, "summary was ${text.length} chars")
        assertTrue(JsonParser.parseString(text).asJsonObject["screenshot"].asString.length <= 300)
    }
}
