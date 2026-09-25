package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
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
import spock.adb.diagnostics.DiagnosticSections
import spock.adb.mcp.tools.DiagnoseCurrentScreenTool
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolResult
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The agent-facing half of Diagnose Current Screen: every section of the shared report, the
 * screenshot of the same moment, and a failure in one part never costing the rest.
 */
class DiagnoseCurrentScreenToolTest {

    private val uiDump = javaClass.getResource("/uidumps/compose-material3.xml")!!.readText()

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x01, 0x02, 0x03,
    )

    private val packageDump = """
        Packages:
          Package [com.example.app] (1234):
            User 0: ceDataInode=1 installed=true
              runtime permissions:
                android.permission.CAMERA: granted=false, flags=[ USER_SET ]
                android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]
    """.trimIndent()

    private fun routedDevice(
        screencapReply: String = Base64.getEncoder().encodeToString(pngBytes),
        packageReply: String = packageDump,
    ): ConnectedDevice {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val issued = command.captured
            val reply = when {
                issued.startsWith("uiautomator dump") -> "UI hierarchy dumped to: /sdcard/spock-adb-ui-dump.xml"
                issued.startsWith("cat ") -> uiDump
                issued.startsWith("pidof") -> "1234"
                issued.startsWith("dumpsys activity activities") ->
                    "  mResumedActivity: ActivityRecord{9f1c u0 com.example.app/.CheckoutActivity t12}"
                issued.startsWith("dumpsys package") -> packageReply
                issued.startsWith("logcat") -> "01-01 00:00:00.000  1234  1234 E MyApp: boom"
                issued.startsWith("screencap") -> screencapReply
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return FakeToolContext.device("emulator-5554").copy(device = device)
    }

    private fun run(arguments: JsonObject = JsonObject(), device: ConnectedDevice = routedDevice()): ToolResult =
        DiagnoseCurrentScreenTool().execute(
            JsonObject().apply {
                addProperty("packageName", "com.example.app")
                arguments.entrySet().forEach { (key, value) -> add(key, value) }
            },
            FakeToolContext(available = listOf(device)),
        )

    private fun reportOf(result: ToolResult): JsonObject =
        JsonParser.parseString(result.content.filterIsInstance<ToolContent.Text>().single().text).asJsonObject

    @Test
    fun `it is registered, so agents can call it`() {
        assertTrue(ToolRegistry.all().any { it.name == "android_diagnose_current_screen" })
    }

    @Test
    fun `every section of the shared report comes back, permissions included`() {
        val report = reportOf(run())

        DiagnosticSections.ALL.forEach { section ->
            assertTrue(report.has(section.id), "missing section ${section.id}: $report")
        }
        assertEquals("CheckoutActivity", report["screen"].asJsonObject["activity"].asString)
        val permissions = report["permissions"].asJsonObject
        assertEquals(1, permissions["granted"].asInt)
        assertEquals("CAMERA", permissions["denied"].asJsonArray.single().asString)
    }

    @Test
    fun `a screenshot of the same moment is attached by default`() {
        val result = run()

        assertFalse(result.isError)
        assertEquals(1, result.content.filterIsInstance<ToolContent.Image>().size)
        assertEquals("attached", reportOf(result)["screenshot"].asString)
    }

    @Test
    fun `the screenshot can be left out`() {
        val result = run(JsonObject().apply { addProperty("screenshot", false) })

        assertTrue(result.content.filterIsInstance<ToolContent.Image>().isEmpty())
        assertFalse(reportOf(result).has("screenshot"))
    }

    @Test
    fun `a screen that blocks capture still returns the diagnosis`() {
        val result = run(device = routedDevice(screencapReply = "not an image"))

        assertFalse(result.isError, "a failed screenshot must not fail the diagnosis")
        val report = reportOf(result)
        assertTrue(report.has("screen"))
        assertTrue(report["screenshot"].asString.isNotBlank())
        assertTrue(result.content.filterIsInstance<ToolContent.Image>().isEmpty())
    }

    @Test
    fun `an app that is not installed is a section error, not an app with no permissions`() {
        val report = reportOf(run(device = routedDevice(packageReply = "Unable to find package: com.example.app")))

        assertFalse(report.has("permissions"), "nothing was read, so nothing may be reported: $report")
        assertTrue(report["sectionErrors"].asJsonObject["permissions"].asString.contains("not installed"))
    }
}
