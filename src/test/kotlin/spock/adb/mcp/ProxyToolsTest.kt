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
    fun `a call that names both host and port never reads the remembered proxy`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)
        // Outside the IDE the real lookup has no Application to ask, so reading it here would throw.
        val tool = SetHttpProxyTool(rememberedProxy = { error("the remembered proxy should not be read") })

        val result = tool.execute(proxyArguments(), context)

        assertFalse(result.isError, result.text())
        assertEquals("192.168.1.10:8888", settings.stored)
    }

    @Test
    fun `omitted host and port use the proxy remembered by the tool window`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)

        val result = SetHttpProxyTool(rememberedProxy = { "10.0.2.2:9090" }).execute(JsonObject(), context)

        assertFalse(result.isError, result.text())
        assertEquals("10.0.2.2:9090", settings.stored)
        assertTrue(
            context.confirmationSummaries.single().contains("10.0.2.2:9090"),
            "the developer should be asked about the resolved proxy: ${context.confirmationSummaries}",
        )
    }

    @Test
    fun `explicit host and port override the remembered proxy`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)

        val result = SetHttpProxyTool(rememberedProxy = { "10.0.2.2:9090" }).execute(proxyArguments(), context)

        assertFalse(result.isError, result.text())
        assertEquals("192.168.1.10:8888", settings.stored)
    }

    @Test
    fun `a host on its own keeps the remembered port`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)
        val arguments = JsonObject().apply { addProperty("host", "192.168.1.10") }

        val result = SetHttpProxyTool(rememberedProxy = { "10.0.2.2:9090" }).execute(arguments, context)

        assertFalse(result.isError, result.text())
        assertEquals("192.168.1.10:9090", settings.stored)
        assertTrue(context.confirmationSummaries.single().contains("192.168.1.10:9090"))
    }

    @Test
    fun `a port on its own keeps the remembered host`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)
        val arguments = JsonObject().apply { addProperty("port", 8888) }

        val result = SetHttpProxyTool(rememberedProxy = { "10.0.2.2:9090" }).execute(arguments, context)

        assertFalse(result.isError, result.text())
        assertEquals("10.0.2.2:8888", settings.stored)
    }

    @Test
    fun `nothing remembered and a missing argument is an error, before asking or writing`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)
        val arguments = JsonObject().apply { addProperty("host", "192.168.1.10") }

        val result = SetHttpProxyTool(rememberedProxy = { "" }).execute(arguments, context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("No port given"), result.text())
        assertTrue(result.text().contains("Pass host and port"), result.text())
        assertTrue(context.confirmations.isEmpty(), "nothing to set, so nothing to ask about")
        assertEquals("null", settings.stored)
    }

    @Test
    fun `an unusable remembered proxy is an error, not an exception`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = true)

        val result = SetHttpProxyTool(rememberedProxy = { "not-a-proxy" }).execute(JsonObject(), context)

        assertTrue(result.isError)
        assertTrue(result.text().contains("'not-a-proxy'"), result.text())
        assertTrue(context.confirmations.isEmpty())
        assertEquals("null", settings.stored)
    }

    @Test
    fun `a declined remembered proxy is never written`() {
        val settings = FakeSettings(initial = "null")
        val context = FakeToolContext(available = listOf(settings.connected), confirmationAnswer = false)

        val result = SetHttpProxyTool(rememberedProxy = { "10.0.2.2:9090" }).execute(JsonObject(), context)

        assertTrue(result.isError)
        assertEquals(listOf("android_set_http_proxy"), context.confirmations)
        assertEquals("null", settings.stored)
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
