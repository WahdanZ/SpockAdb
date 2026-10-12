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
import spock.adb.mcp.tools.GetNetworkInfoTool
import java.util.concurrent.TimeUnit

/**
 * `ip -o addr show` keeps a `\` and padding where its multi-line form would wrap, which an agent
 * read as part of the interface name (`scope host lo\ valid_lft`, #182).
 */
class NetworkInfoToolTest {

    /** As an API 34 emulator prints it, padding included. */
    private val ipAddr = listOf(
        "1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever",
        "1: lo    inet6 ::1/128 scope host \\       valid_lft forever preferred_lft forever",
        "15: eth0    inet 10.0.2.15/8 brd 10.255.255.255 scope global eth0\\       " +
            "valid_lft forever preferred_lft forever",
        "16: wlan0    inet6 fec0::77b2:cf4e:e69b:f760/64 scope site temporary dynamic \\       " +
            "valid_lft 86193sec preferred_lft 14193sec",
    ).joinToString("\n")

    @Test
    fun `the line-continuation markers become one space`() {
        assertEquals(
            listOf(
                "1: lo    inet 127.0.0.1/8 scope host lo valid_lft forever preferred_lft forever",
                "1: lo    inet6 ::1/128 scope host valid_lft forever preferred_lft forever",
                "15: eth0    inet 10.0.2.15/8 brd 10.255.255.255 scope global eth0 valid_lft forever " +
                    "preferred_lft forever",
                "16: wlan0    inet6 fec0::77b2:cf4e:e69b:f760/64 scope site temporary dynamic valid_lft 86193sec " +
                    "preferred_lft 14193sec",
            ),
            GetNetworkInfoTool.withoutContinuations(ipAddr).lines(),
        )
    }

    @Test
    fun `the tool's interfaces have no backslashes`() {
        val device = mockk<IDevice>(relaxed = true)
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every { device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>()) } answers {
            val bytes = (if (command.captured == "ip -o addr show") ipAddr else "1").toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))

        val text = GetNetworkInfoTool().execute(JsonObject(), context).text()

        assertTrue(text.contains("scope host lo valid_lft forever"), text)
        assertFalse(text.contains('\\'), text)
    }
}
