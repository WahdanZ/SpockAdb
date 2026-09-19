package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading an app's identity out of `dumpsys package`.
 *
 * The fields sit among a few hundred lines of permission state whose order and neighbours have
 * both changed across releases, so each is found by name. `uid=` is the trap: it appears on
 * permission entries, where it belongs to the permission's declarer rather than to this app.
 */
class AppInfoTest {

    private val dumpsys = """
        Permissions:
          Permission [com.example.PRIVATE] (a1b2):
            sourcePackage=com.example.other
            uid=10999 gids=[] type=0 prot=signature
        Packages:
          Package [com.example.app] (c3d4):
            appId=10188
            versionCode=7 minSdk=24 targetSdk=36
            versionName=2.4.1
            dataDir=/data/user/0/com.example.app
    """.trimIndent()

    @Test
    fun `the app's own UID is read, not a permission's`() {
        val info = AppInfo.parse("com.example.app", dumpsys, pidof = "")

        assertEquals("10188", info.uid, "uid=10999 belongs to the package that declared a permission")
    }

    @Test
    fun `version name and code are read whatever surrounds them`() {
        val info = AppInfo.parse("com.example.app", dumpsys, pidof = "")

        assertEquals("2.4.1", info.versionName)
        assertEquals("7", info.versionCode, "the value ends at the space before minSdk")
        assertEquals("2.4.1 (7)", info.version())
    }

    @Test
    fun `an app that is not running says so rather than showing a blank`() {
        val stopped = AppInfo.parse("com.example.app", dumpsys, pidof = "")
        assertNull(stopped.pid)
        assertFalse(stopped.isRunning)

        val running = AppInfo.parse("com.example.app", dumpsys, pidof = "4821\n")
        assertEquals("4821", running.pid)
        assertTrue(running.isRunning)
    }

    @Test
    fun `more than one process answers with the first pid`() {
        // A package with a :remote process has pidof answer with both.
        val info = AppInfo.parse("com.example.app", dumpsys, pidof = "4821 4822")

        assertEquals("4821", info.pid)
    }

    @Test
    fun `a device that answered nothing leaves the fields empty rather than inventing them`() {
        val info = AppInfo.parse("com.example.app", dumpsys = "", pidof = "")

        assertNull(info.versionName)
        assertNull(info.versionCode)
        assertNull(info.uid)
        assertNull(info.version())
        assertEquals("com.example.app", info.packageName)
    }

    @Test
    fun `half an answer is still worth showing`() {
        val info = AppInfo.parse("com.example.app", "versionName=1.0", pidof = "")

        assertEquals("1.0", info.version())
    }
}
