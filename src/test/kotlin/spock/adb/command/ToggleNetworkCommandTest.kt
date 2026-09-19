package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.getNetworkState
import java.util.concurrent.TimeUnit

/**
 * The Wi-Fi and mobile data toggles, which decide what to do by first asking the device what it
 * is set to — so everything about them depends on that answer being read correctly.
 *
 * `settings get` answers with a DOS line ending, and the state was matched against the exact
 * string, so anything not read as "1" is treated as off. A toggle that always believes the
 * connection is off is a button that can only ever switch it on.
 */
class ToggleNetworkCommandTest {

    private val project: Project = mockk(relaxed = true)

    /** A device whose `settings` and `svc` agree with each other, as a real one's do. */
    private class FakeNetworkDevice(
        wifi: String = "1",
        mobile: String = "1",
        private val api: Int = 29,
        /** A device that runs the command, says nothing, and changes nothing — as many do. */
        private val refuses: Boolean = false,
        private val complaint: String = "",
    ) {
        val commands = mutableListOf<String>()
        private val settings = mutableMapOf("wifi_on" to wifi, "mobile_data" to mobile)
        val device: IDevice = mockk(relaxed = true)

        fun setting(name: String): String = settings.getValue(name)

        init {
            val command = slot<String>()
            val receiver = slot<IShellOutputReceiver>()
            every {
                device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
            } answers {
                commands += command.captured
                // As a device answers: the value, then the line ending adb puts after it.
                val bytes = "${reply(command.captured)}\r\n".toByteArray()
                receiver.captured.addOutput(bytes, 0, bytes.size)
                receiver.captured.flush()
            }
        }

        private fun reply(command: String): String = when {
            command.startsWith("getprop ro.build.version.sdk") -> api.toString()
            command.startsWith("settings get global ") -> settings.getValue(command.substringAfterLast(' '))
            // Runs, says whatever it says, and changes nothing — as a device that is not
            // allowed to switch Wi-Fi from the shell does.
            refuses -> complaint
            else -> write(command)
        }

        /** Which setting a `svc` or `cmd -w wifi` line changes, and to what. */
        private fun write(command: String): String {
            val on = command.endsWith("enable") || command.endsWith("enabled")
            val name = if (command.contains("wifi")) "wifi_on" else "mobile_data"
            settings[name] = if (on) "1" else "0"
            return ""
        }
    }

    @Test
    fun `the state is read through the line ending the device appends`() {
        val fake = FakeNetworkDevice(wifi = "1", mobile = "0")

        assertEquals(NetworkState.ENABLED, fake.device.getNetworkState(Network.WIFI))
        assertEquals(NetworkState.DISABLED, fake.device.getNetworkState(Network.MOBILE))
    }

    @Test
    fun `a connection that is on is switched off, and the device is asked again to be sure`() {
        val fake = FakeNetworkDevice(wifi = "1")

        val message = ToggleNetworkCommand().execute(Network.WIFI, project, fake.device)

        assertEquals("0", fake.setting("wifi_on"), "the device must end up off")
        assertEquals("Disabled Wi-Fi", message)
        assertEquals(
            listOf("settings get global wifi_on", "svc wifi disable", "settings get global wifi_on"),
            fake.commands.filter { !it.startsWith("getprop") },
            "the state is read before the change and again after it",
        )
    }

    @Test
    fun `a connection that is off is switched on`() {
        val fake = FakeNetworkDevice(wifi = "0")

        val message = ToggleNetworkCommand().execute(Network.WIFI, project, fake.device)

        assertEquals("1", fake.setting("wifi_on"))
        assertEquals("Enabled Wi-Fi", message)
        assertTrue("svc wifi enable" in fake.commands)
    }

    @Test
    fun `Android 11 and later go through the command that is still allowed to work`() {
        val fake = FakeNetworkDevice(wifi = "1", api = 30)

        ToggleNetworkCommand().execute(Network.WIFI, project, fake.device)

        assertTrue("cmd -w wifi set-wifi-enabled disabled" in fake.commands, fake.commands.toString())
        assertFalse(fake.commands.any { it.startsWith("svc wifi") }, "svc is the fallback, not the first try")
        assertEquals("0", fake.setting("wifi_on"))
    }

    @Test
    fun `mobile data toggles its own setting, not Wi-Fi's`() {
        val fake = FakeNetworkDevice(wifi = "1", mobile = "1", api = 34)

        ToggleNetworkCommand().execute(Network.MOBILE, project, fake.device)

        assertEquals("0", fake.setting("mobile_data"))
        assertEquals("1", fake.setting("wifi_on"), "Wi-Fi must not be touched")
        // Mobile data has no `cmd` equivalent across the range, so it stays on svc.
        assertTrue("svc data disable" in fake.commands)
    }

    @Test
    fun `a device that runs the command and changes nothing is not reported as changed`() {
        val fake = FakeNetworkDevice(wifi = "1", refuses = true)

        val thrown = assertThrows<IllegalStateException> {
            ToggleNetworkCommand().execute(Network.WIFI, project, fake.device)
        }

        assertEquals("1", fake.setting("wifi_on"), "nothing changed, which is the point")
        assertTrue(thrown.message!!.contains("Wi-Fi is still enabled"), thrown.message)
        assertTrue(thrown.message!!.contains("Android 10"), "say why a modern device refuses")
    }

    @Test
    fun `whatever the shell complained about is carried into the failure`() {
        val fake = FakeNetworkDevice(
            wifi = "0",
            refuses = true,
            complaint = "Killed",
        )

        val thrown = assertThrows<IllegalStateException> {
            ToggleNetworkCommand().execute(Network.WIFI, project, fake.device)
        }

        assertTrue(thrown.message!!.contains("Killed"), thrown.message)
    }

    @Test
    fun `a device that answers nothing is not reported as on`() {
        val fake = FakeNetworkDevice(wifi = "")

        // Unknown reads as off, which is the safe way round: the toggle then tries to switch
        // the connection on rather than announcing it has switched something off.
        assertEquals(NetworkState.DISABLED, fake.device.getNetworkState(Network.WIFI))
    }
}
