package spock.adb.device.ops

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Reading the screen, against a scripted device and no IDE at all.
 *
 * That last part is the point of the class under test: these reads never needed a `Project`,
 * and now that they do not ask for one they can be tested without one.
 */
class InspectionOperationsTest {

    @Test
    fun `the resumed activity is read from mResumedActivity when the device reports it`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.contains("mResumedActivity")) RESUMED else ""
        }

        assertEquals("com.example.app.ui.MainActivity", InspectionOperations(device).currentActivity())
        assertEquals(1, commands.size, "the fallback must not be asked for when the first read answered")
    }

    @Test
    fun `Android 13 and later fall back to topResumedActivity`() {
        // mResumedActivity was removed there, so the first read comes back empty.
        val (device, commands) = scriptedDevice { command ->
            if (command.contains("topResumedActivity")) TOP_RESUMED else ""
        }

        assertEquals("com.example.app.HomeActivity", InspectionOperations(device).currentActivity())
        assertEquals(2, commands.size, commands.toString())
    }

    @Test
    fun `a screen with nothing resumed is null, not an empty name`() {
        val (device, _) = scriptedDevice { "" }

        assertNull(InspectionOperations(device).currentActivity())
    }

    @Test
    fun `the activity stack is read from the history lines on a modern device`() {
        val (device, commands) = scriptedDevice { command ->
            when {
                command.startsWith("getprop ro.build.version.sdk") -> "34"
                command.contains("Hist") -> HISTORY
                else -> ""
            }
        }

        val stack = InspectionOperations(device).activityStack()

        assertEquals(listOf(PACKAGE), stack.map { it.appPackage })
        assertTrue(commands.any { it.contains("grep -E 'Hist|mResumedActivity'") }, commands.toString())
    }

    @Test
    fun `a pre-Honeycomb device is read with the running-activities dump instead`() {
        val (device, commands) = scriptedDevice { command ->
            if (command.startsWith("getprop ro.build.version.sdk")) "10" else ""
        }

        InspectionOperations(device).activityStack()

        assertTrue(commands.any { it.contains("Running activities") }, commands.toString())
        assertTrue(commands.none { it.contains("Hist") }, commands.toString())
    }

    @Test
    fun `fragments are dumped for the app by name, not for whatever is in front`() {
        // `dumpsys activity top` reports the foreground app, which need not be the one asked
        // about, and on Android 13 reports no fragment state at all.
        val (device, commands) = scriptedDevice { "" }

        InspectionOperations(device).fragments(PACKAGE)

        assertEquals(listOf("dumpsys activity '$PACKAGE'"), commands)
    }

    @Test
    fun `a package name that could reach the shell is refused before anything is sent`() {
        val (device, commands) = scriptedDevice { "" }

        assertThrows<IllegalArgumentException> {
            InspectionOperations(device).fragments("com.example.app; rm -rf /")
        }

        assertTrue(commands.isEmpty(), "nothing may be sent: $commands")
    }

    @Test
    fun `labels for several packages are read in one round trip per step`() {
        // A call per app was felt: this feeds a popup that opens on a click.
        val (device, commands) = scriptedDevice { "" }

        InspectionOperations(device).appLabels(listOf("com.a", "com.b", "com.a"))

        assertEquals(1, commands.size, commands.toString())
        assertTrue(commands.single().contains("for p in 'com.a' 'com.b'"), commands.single())
    }

    @Test
    fun `nothing is asked of the device when no package survives validation`() {
        val (device, commands) = scriptedDevice { "" }

        assertEquals(emptyMap<String, String>(), InspectionOperations(device).appLabels(listOf("bad name")))
        assertTrue(commands.isEmpty(), commands.toString())
    }

    private companion object {
        const val RESUMED =
            "  mResumedActivity: ActivityRecord{a1b2c3 u0 com.example.app/.ui.MainActivity t42}"
        const val TOP_RESUMED =
            "  topResumedActivity=ActivityRecord{9f8e7d u0 com.example.app/.HomeActivity t7}"
        const val HISTORY =
            "* Hist #1: ActivityRecord{aaa u0 com.example.app/.DetailActivity t10}\n" +
                "* Hist #0: ActivityRecord{bbb u0 com.example.app/.ListActivity t10}"
    }
}
