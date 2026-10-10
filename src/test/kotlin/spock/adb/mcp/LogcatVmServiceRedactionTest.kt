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
import spock.adb.mcp.tools.GetLogcatTool
import spock.adb.mcp.tools.RunAdbCommandTool
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolResult
import java.util.concurrent.TimeUnit

/**
 * A Flutter debug build logs its VM Service address, token included, and the token lets whoever
 * holds it run code in the app. Raw logcat reaches an agent by several roads; none may carry it.
 */
class LogcatVmServiceRedactionTest {

    private val logcat = javaClass.getResource("/vmservice/logcat-vm-service.txt")!!.readText()

    /** The loopback tokens in the fixture: the only kind a VM Service serves on. */
    private val tokens = listOf("oLdToKeN1Ab", "AbCdEfGh123", "OtHeRaPp99x", "NeWeRpRoC4s")

    private fun device(): ConnectedDevice {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val reply = when {
                command.captured.startsWith("pidof") -> "5021"
                command.captured.startsWith("logcat") -> logcat
                else -> ""
            }
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        return FakeToolContext.device("emulator-5554").copy(device = device)
    }

    private fun textOf(result: ToolResult) = result.content.filterIsInstance<ToolContent.Text>().joinToString("\n") {
        it.text
    }

    private fun assertRedacted(text: String) {
        tokens.forEach { token -> assertFalse(text.contains(token), "$token must not leave Spock: $text") }
        assertTrue(text.contains("http://127.0.0.1:43181/<redacted>/"), text)
        assertTrue(text.contains("hello from main()"), "the rest of the log must survive: $text")
    }

    @Test
    fun `android_get_logcat returns the log without the token`() {
        val result = GetLogcatTool().execute(JsonObject(), FakeToolContext(available = listOf(device())))

        assertRedacted(textOf(result))
    }

    @Test
    fun `the full debug context returns the log without the token`() {
        val arguments = JsonObject().apply {
            addProperty("format", "full")
            add("include", JsonArray().apply { add("logcat") })
        }

        val result = DebugContextTool().execute(arguments, FakeToolContext(available = listOf(device())))

        assertRedacted(textOf(result))
    }

    @Test
    fun `logcat run through the escape hatch comes back without the token`() {
        val arguments = JsonObject().apply {
            addProperty("command", "logcat -d")
            addProperty("reason", "test")
        }
        val context = FakeToolContext(available = listOf(device()), confirmationAnswer = true)

        assertRedacted(textOf(RunAdbCommandTool().execute(arguments, context)))
    }

    @Test
    fun `the recorded call, which the Timeline and the audit trail show, holds no token`() {
        val calls = mutableListOf<McpCall>()
        val protocol = McpProtocol(
            contextProvider = { FakeToolContext(available = listOf(device())) },
            auditLog = { calls += it },
        )

        val response = protocol.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"android_get_logcat"}}""",
        )!!

        assertRedacted(calls.single().result)
        tokens.forEach { token -> assertFalse(response.contains(token), response) }
    }
}
