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
import spock.adb.command.PushMessage
import spock.adb.mcp.tools.SendPushMessageTool
import spock.adb.mcp.tools.ToolSafety
import java.util.concurrent.TimeUnit

/** `android_send_push_message`: a refused or undelivered push is an error, never "sent". */
class PushMessageToolTest {

    private val commands = mutableListOf<String>()

    private fun contextReplying(reply: String): FakeToolContext {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            commands += command.captured
            val bytes = reply.toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        every { device.name } returns "emulator-5554"
        return FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))
    }

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `it is a safe action`() {
        assertEquals(ToolSafety.SAFE_ACTION, SendPushMessageTool().safety)
    }

    @Test
    fun `an accepted push is a text result, sent to the project's app`() {
        val result = SendPushMessageTool().execute(
            args("""{"data":{"orderId":"42"},"title":"Shipped"}"""),
            contextReplying(reply(uid = "0")),
        )

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("accepted"), result.text())
        val sent = commands.single()
        assertTrue(sent.contains("-p 'com.example.app'"), sent)
        assertTrue(sent.contains("--es 'orderId' '42'"), sent)
        assertTrue(sent.contains("--es '${PushMessage.NOTIFICATION_TITLE}' 'Shipped'"), sent)
    }

    @Test
    fun `a refused push is an error that says why and what to do`() {
        val denial = "Permission Denial: broadcasting Intent { act=${PushMessage.ACTION} pkg=com.example.app } " +
            "requires com.google.android.c2dm.permission.SEND due to receiver com.example.app/.R"

        val result = SendPushMessageTool().execute(
            args("""{"data":{"a":"b"}}"""),
            contextReplying(reply(uid = "2000", log = denial)),
        )

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("adb root"), result.text())
    }

    @Test
    fun `an empty message is refused before the device is touched`() {
        val result = SendPushMessageTool().execute(args("{}"), contextReplying(reply()))

        assertTrue(result.isError, result.text())
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `a data value that is not a string or number is refused`() {
        val result = SendPushMessageTool().execute(args("""{"data":{"a":{"nested":1}}}"""), contextReplying(reply()))

        assertTrue(result.isError, result.text())
        assertTrue(commands.isEmpty())
    }

    private fun reply(uid: String = "0", log: String = "") = listOf(
        "@@spock-uid", uid,
        "@@spock-debuggable", "1",
        "@@spock-receivers", "com.example.app/.R",
        "@@spock-broadcast", "Broadcast completed: result=0",
        "@@spock-log", log,
    ).joinToString("\n")
}
