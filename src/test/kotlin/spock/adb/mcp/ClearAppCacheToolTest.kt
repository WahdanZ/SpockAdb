package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ClearAppCacheTool
import java.util.concurrent.TimeUnit

/**
 * `android_clear_app_cache` is a safe action: it must run without asking, and when the device
 * refuses it must say what the device said.
 */
class ClearAppCacheToolTest {

    private val pkg = "com.example.app"

    @Test
    fun `clears without asking for confirmation`() {
        val (context, _) = contextWith { command ->
            if (command.startsWith("pm list packages")) "package:$pkg" else ""
        }

        val result = ClearAppCacheTool().execute(JsonObject(), context)

        assertFalse(result.isError, result.text())
        assertTrue(context.confirmations.isEmpty(), "a safe action must not ask")
    }

    @Test
    fun `a run-as refusal is an error that quotes the device`() {
        val (context, _) = contextWith { command ->
            when {
                command.startsWith("pm list packages") -> "package:$pkg"
                command.startsWith("run-as") -> "run-as: Could not set capabilities: Operation not permitted"
                else -> ""
            }
        }

        val result = ClearAppCacheTool().execute(JsonObject(), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("Operation not permitted"), result.text())
    }

    @Test
    fun `an uninstalled package is an error and nothing is removed`() {
        val (context, commands) = contextWith { "" }

        val result = ClearAppCacheTool().execute(JsonObject(), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains(pkg), result.text())
        assertTrue(commands.none { it.contains("rm -rf") }, "no rm may be sent: $commands")
    }

    private fun contextWith(reply: (String) -> String): Pair<FakeToolContext, List<String>> {
        val device = mockk<IDevice>(relaxed = true)
        val commands = mutableListOf<String>()
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            commands += command.captured
            val bytes = reply(command.captured).toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
        val connected = FakeToolContext.device("emulator-5554").copy(device = device)
        return FakeToolContext(available = listOf(connected)) to commands
    }
}
