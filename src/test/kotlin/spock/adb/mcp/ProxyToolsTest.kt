package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.tools.ClearHttpProxyTool
import spock.adb.mcp.tools.SetHttpProxyTool
import java.util.concurrent.TimeUnit

/**
 * The proxy tools report what the device holds after a write, never what they sent. These
 * run against a fake `settings` store that can be told to ignore writes, which is the case a
 * real device produces and the reason the read-back exists.
 */
class ProxyToolsTest {

    /** A device whose global http_proxy starts as [initial] and, if [acceptsWrites], changes. */
    private class FakeSettings(initial: String, private val acceptsWrites: Boolean = true) {
        var stored = initial
            private set

        val connected: ConnectedDevice = run {
            val device = mockk<IDevice>(relaxed = true)
            val command = slot<String>()
            val receiver = slot<IShellOutputReceiver>()
            every {
                device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
            } answers {
                val sent = command.captured
                if (sent.startsWith(PUT) && acceptsWrites) {
                    stored = sent.removePrefix(PUT).removeSurrounding("'")
                } else if (sent == GET) {
                    val bytes = stored.toByteArray()
                    receiver.captured.addOutput(bytes, 0, bytes.size)
                }
                Unit
            }
            FakeToolContext.device("emulator-5554").copy(device = device)
        }
    }

    private fun proxyArguments() = JsonObject().apply {
        addProperty("host", "192.168.1.10")
        addProperty("port", 8888)
    }

    @Test
    fun `an approved proxy is written and confirmed from the device`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)

        val result = SetHttpProxyTool().execute(proxyArguments(), context)

        assertFalse(result.isError, result.text())
        assertEquals("HTTP proxy set to 192.168.1.10:8888.", result.text())
        assertEquals("192.168.1.10:8888", settings.stored)
        assertEquals(listOf("android_set_http_proxy"), context.confirmations)
    }

    @Test
    fun `a proxy the device did not keep is an error`() {
        val settings = FakeSettings(initial = "null", acceptsWrites = false)
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)

        val result = SetHttpProxyTool().execute(proxyArguments(), context)

        assertTrue(result.isError)
        assertEquals(
            "Asked the device for 192.168.1.10:8888 but it reports no proxy. The setting did not stick.",
            result.text(),
        )
    }

    @Test
    fun `clearing reports a direct connection once the device agrees`() {
        val settings = FakeSettings(initial = "192.168.1.10:8888")

        val result = ClearHttpProxyTool().execute(JsonObject(), FakeToolContext(available = listOf(settings.connected)))

        assertFalse(result.isError, result.text())
        assertEquals(":0", settings.stored, "Android clears the proxy by writing :0")
    }

    @Test
    fun `a clear the device did not keep is an error, not a success`() {
        val settings = FakeSettings(initial = "192.168.1.10:8888", acceptsWrites = false)

        val result = ClearHttpProxyTool().execute(JsonObject(), FakeToolContext(available = listOf(settings.connected)))

        assertTrue(result.isError)
        assertEquals("Cleared the proxy but the device still reports 192.168.1.10:8888.", result.text())
    }

    private companion object {
        const val PUT = "settings put global http_proxy "
        const val GET = "settings get global http_proxy"
    }
}
