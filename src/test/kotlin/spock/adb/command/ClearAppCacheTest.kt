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
 * the device, and that success is decided by the `rm`'s own exit status rather than by
 * reading the directories back afterwards — a readback mis-reported an empty cache as two
 * leftover files, and raced a running app refilling its cache.
 */
class ClearAppCacheTest {

    private val pkg = "com.example.app"

    @Test
    fun `clear command removes only the relative cache directories and reports its own status`() {
        assertEquals(
            "run-as 'com.example.app' sh -c 'rm -rf ./cache ./code_cache; echo rc=$?'",
            AppCacheShell.clearCommand(pkg),
        )
        // Built from the character rather than written inline, so a `$` lost to Kotlin string
        // templating cannot go unnoticed by matching an equally broken expectation above.
        assertTrue(AppCacheShell.clearCommand(pkg).endsWith("echo rc=" + '$' + "?'"))
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
            "run-as 'com.evil'\\''; rm -rf /' sh -c 'rm -rf ./cache ./code_cache; echo rc=$?'",
            AppCacheShell.clearCommand("com.evil'; rm -rf /"),
        )
    }

    @Test
    fun `rc=0 is success, whatever else the device printed first`() {
        listOf(
            "rc=0",
            "rc=0\n",
            "  rc=0  ",
            // The echo only runs if the script ran, so anything ahead of it is noise.
            "WARNING: linker: unused DT entry\nrc=0",
        ).forEach {
            assertNull(AppCacheShell.failureMessage(pkg, it), "'$it' should be success")
        }
    }

    @Test
    fun `a non-zero status is a failure that surfaces what rm said`() {
        val message = AppCacheShell.failureMessage(pkg, "rm: ./cache/x: Permission denied\nrc=1")!!

        assertTrue(message.contains("Permission denied"), message)
        assertTrue(message.contains(pkg), message)
    }

    @Test
    fun `a non-zero status with nothing else still fails rather than passing silently`() {
        val message = AppCacheShell.failureMessage(pkg, "rc=1")!!

        assertTrue(message.contains("1"), message)
    }

    @Test
    fun `no status line at all is a failure, because that is what a refused run-as looks like`() {
        listOf("", "\n", "   \n ").forEach {
            val message = AppCacheShell.failureMessage(pkg, it)
            assertTrue(message != null && message.contains("no exit status"), "'$it' must not read as success")
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
    fun `an rc printed by the app itself does not decide the outcome`() {
        // Only the trailing status line is ours; a stray `rc=0` in the app's own output is not.
        val message = AppCacheShell.failureMessage(pkg, "rc=0 something the app logged\nrun-as: unknown package")

        assertTrue(message != null && message.contains("run-as could not reach"), message)
    }

    @Test
    fun `an rc line is not success when another non-empty line follows it`() {
        val message = AppCacheShell.failureMessage(pkg, "rc=0\nrun-as: unknown package")

        assertTrue(message != null && message.contains("run-as could not reach"), message)
    }

    @Test
    fun `clearing takes exactly one shell round trip`() {
        // The second round trip is what raced a running app refilling its own cache.
        val (device, commands) = scriptedDevice { "rc=0" }

        device.clearAppCacheOrThrow(pkg)

        assertEquals(listOf(AppCacheShell.clearCommand(pkg)), commands)
    }

    @Test
    fun `a run-as refusal throws with the device's own words`() {
        val (device, commands) = scriptedDevice { "run-as: Package '$pkg' is not debuggable" }

        val thrown = assertThrows<IllegalStateException> { device.clearAppCacheOrThrow(pkg) }

        assertTrue(thrown.message!!.contains("debuggable"), thrown.message)
        assertEquals(listOf(AppCacheShell.clearCommand(pkg)), commands)
    }

    @Test
    fun `a failed rm throws and names what it could not remove`() {
        val (device, _) = scriptedDevice { "rm: ./code_cache/locked: Permission denied\nrc=1" }

        val thrown = assertThrows<IllegalStateException> { device.clearAppCacheOrThrow(pkg) }

        assertTrue(thrown.message!!.contains("code_cache/locked"), thrown.message)
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
