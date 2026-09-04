package spock.adb.commandcenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DangerousCommandsTest {

    @Test
    fun `ordinary inspection commands are safe`() {
        listOf("pm list packages", "dumpsys battery", "ps -A", "logcat -d", "getprop")
            .forEach { assertEquals(DangerousCommands.Verdict.SAFE, DangerousCommands.classify(it), it) }
    }

    @Test
    fun `commands that destroy app or system state need confirmation`() {
        listOf(
            "pm clear com.example.app",
            "pm uninstall com.example.app",
            "pm revoke com.example.app android.permission.CAMERA",
            "rm /sdcard/file.txt",
            "reboot",
            "settings put global airplane_mode_on 1",
        ).forEach {
            assertEquals(DangerousCommands.Verdict.DESTRUCTIVE, DangerousCommands.classify(it), it)
        }
    }

    @Test
    fun `commands that wipe the device are refused outright`() {
        listOf("rm -rf /", "recovery --wipe_data", "mkfs.ext4 /dev/block/x", "dd if=/dev/zero of=/dev/block/x")
            .forEach {
                assertEquals(DangerousCommands.Verdict.REFUSED, DangerousCommands.classify(it), it)
            }
    }

    @Test
    fun `classification ignores case`() {
        assertEquals(DangerousCommands.Verdict.DESTRUCTIVE, DangerousCommands.classify("PM CLEAR com.example"))
        assertEquals(DangerousCommands.Verdict.REFUSED, DangerousCommands.classify("RM -RF /"))
    }

    @Test
    fun `an empty command is safe rather than an error`() {
        assertEquals(DangerousCommands.Verdict.SAFE, DangerousCommands.classify("   "))
    }

    @Test
    fun `flagged commands explain themselves and safe ones do not`() {
        assertNotNull(DangerousCommands.explain("pm clear com.example.app"))
        assertNotNull(DangerousCommands.explain("rm -rf /"))
        assertNull(DangerousCommands.explain("ps -A"))
    }
}
