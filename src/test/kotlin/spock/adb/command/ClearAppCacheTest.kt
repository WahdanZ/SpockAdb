package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * "Clear cache" must never turn into "clear data". These tests pin the command that reaches
 * the device, how `run-as` refusals are reported, and that success is proven by a readback
 * rather than assumed from a silent `rm`.
 */
class ClearAppCacheTest {

    private val pkg = "com.example.app"

    @Test
    fun `clear command removes only the relative cache directories under run-as`() {
        assertEquals(
            "run-as 'com.example.app' sh -c 'rm -rf ./cache ./code_cache'",
            AppCacheShell.clearCommand(pkg),
        )
    }

    @Test
    fun `clear command never names app data or an absolute path`() {
        val command = AppCacheShell.clearCommand(pkg)

        listOf("/data", "files", "databases", "shared_prefs").forEach {
            assertFalse(command.contains(it), "cache clear must not touch '$it': $command")
        }
        val absolute = command.split(' ').map { it.trim('\'') }.filter { it.startsWith("/") }
        assertTrue(absolute.isEmpty(), "no absolute paths allowed, found $absolute")
    }

    @Test
    fun `a quote in the package name cannot escape into a second command`() {
        assertEquals(
            "run-as 'com.evil'\\''; rm -rf /' sh -c 'rm -rf ./cache ./code_cache'",
            AppCacheShell.clearCommand("com.evil'; rm -rf /"),
        )
    }

    @Test
    fun `silence is success`() {
        listOf("", "\n", "   \n ").forEach {
            assertNull(AppCacheShell.failureMessage(pkg, it), "blank output '$it' is not a failure")
        }
    }

    @Test
    fun `a non-debuggable build points at Clear Data`() {
        val message = AppCacheShell.failureMessage(pkg, "run-as: Package '$pkg' is not debuggable")!!

        assertTrue(message.contains(pkg), message)
        assertTrue(message.contains("debuggable"), message)
        assertTrue(message.contains("Clear Data"), message)
    }

    @Test
    fun `an unknown package names the package and does not claim success`() {
        val message = AppCacheShell.failureMessage(pkg, "run-as: unknown package: $pkg")!!

        assertTrue(message.contains(pkg), message)
        assertFalse(message.contains("Cleared"), message)
    }

    @Test
    fun `an unrecognised refusal is quoted verbatim`() {
        val said = "run-as: Could not set capabilities: Operation not permitted"

        val message = AppCacheShell.failureMessage(pkg, said)!!

        assertTrue(message.contains(said), message)
    }

    @Test
    fun `leftovers are the non-blank lines of the readback`() {
        assertTrue(AppCacheShell.leftovers("").isEmpty())
        assertEquals(listOf("images", "http-cache"), AppCacheShell.leftovers("images\nhttp-cache\n"))
    }

    @Test
    fun `clears first and reads back second`() {
        val (device, commands) = scriptedDevice { "" }

        device.clearAppCacheOrThrow(pkg)

        assertEquals(
            listOf(AppCacheShell.clearCommand(pkg), AppCacheShell.verifyCommand(pkg)),
            commands,
            "the readback proves nothing unless it runs after the rm",
        )
    }

    @Test
    fun `a run-as refusal throws and skips the readback`() {
        val (device, commands) = scriptedDevice { "run-as: Package '$pkg' is not debuggable" }

        val thrown = assertThrows<IllegalStateException> { device.clearAppCacheOrThrow(pkg) }

        assertTrue(thrown.message!!.contains("debuggable"), thrown.message)
        assertEquals(listOf(AppCacheShell.clearCommand(pkg)), commands)
    }

    @Test
    fun `a readback that still finds files throws and names them`() {
        val (device, _) = scriptedDevice { command ->
            if (command == AppCacheShell.verifyCommand(pkg)) "images\n" else ""
        }

        val thrown = assertThrows<IllegalStateException> { device.clearAppCacheOrThrow(pkg) }

        assertTrue(thrown.message!!.contains("images"), thrown.message)
    }

    /** A device whose shell answers each command with [reply], recording commands in order. */
    private fun scriptedDevice(reply: (String) -> String): Pair<IDevice, List<String>> {
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
        return device to commands
    }
}
