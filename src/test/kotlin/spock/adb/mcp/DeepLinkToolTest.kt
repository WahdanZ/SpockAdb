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
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.OpenDeepLinkTool
import java.util.concurrent.TimeUnit

/**
 * `android_open_deep_link` decided by looking for the word "Error", which a `Permission
 * Denial` trace does not contain — so an agent was told a refused link had opened and then
 * walked into a wrong-screen assertion it could not explain.
 */
class DeepLinkToolTest {

    /** A device whose shell answers with [reply], recording the commands it was sent. */
    private class FakeDevice(reply: String) {
        val commands = mutableListOf<String>()

        val connected: ConnectedDevice = run {
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
            FakeToolContext.device("emulator-5554").copy(device = device)
        }
    }

    private fun arguments(packageName: String? = null) = JsonObject().apply {
        addProperty("uri", URI)
        packageName?.let { addProperty("packageName", it) }
    }

    private fun contextFor(device: FakeDevice) = FakeToolContext(available = listOf(device.connected))

    @Test
    fun `a refused deep link is an error result`() {
        val device = FakeDevice(PERMISSION_DENIAL)

        val result = OpenDeepLinkTool().execute(arguments(), contextFor(device))

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("not exported"), result.text())
        // The device's own words, not just the plugin's summary.
        assertTrue(result.text().contains("Permission Denial"), result.text())
    }

    @Test
    fun `a started deep link is a text result naming the activity`() {
        val device = FakeDevice(
            """
            Starting: Intent { act=android.intent.action.VIEW dat=$URI }
            Status: ok
            Activity: com.example/.DeepLinkActivity
            TotalTime: 214
            """.trimIndent(),
        )

        val result = OpenDeepLinkTool().execute(arguments(), contextFor(device))

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("com.example/.DeepLinkActivity"), result.text())
    }

    @Test
    fun `the packageName argument reaches the device`() {
        val device = FakeDevice("Status: ok")

        OpenDeepLinkTool().execute(arguments(packageName = "com.example.app"), contextFor(device))

        val sent = device.commands.single()
        assertTrue(sent.contains(" -p 'com.example.app'"), sent)
        assertTrue(sent.contains("am start -W "), sent)
    }

    private companion object {
        const val URI = "myapp://product/42"

        val PERMISSION_DENIAL = """
            Starting: Intent { act=android.intent.action.VIEW dat=myapp://product/42 }
            Security exception: Permission Denial: starting Intent { cmp=com.example/.DeepLinkActivity }
             from null (pid=9123, uid=2000) not exported from uid 10234
            java.lang.SecurityException: Permission Denial: starting Intent
        """.trimIndent()
    }
}
