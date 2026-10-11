package spock.adb.context

import com.android.ddmlib.IDevice
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState

/** The choices the shared selection makes without being asked. */
class SelectionRulesTest {

    private fun device(serial: String, state: DeviceState = DeviceState.ONLINE) = ConnectedDevice(
        mockk<IDevice>(relaxed = true),
        DeviceInfo(
            serialNumber = serial,
            model = "Pixel 8",
            manufacturer = "Google",
            androidVersion = "15",
            apiLevel = 35,
            abi = "arm64-v8a",
            isEmulator = false,
            state = state,
        ),
    )

    @Test
    fun `the preferred device is kept while it is connected`() {
        val devices = listOf(device("a"), device("b"))
        assertEquals("b", SelectionRules.nextDevice(devices, preferred = "b")?.serialNumber)
    }

    @Test
    fun `a preferred device that is gone falls back to the first usable one`() {
        val devices = listOf(device("offline", DeviceState.OFFLINE), device("b"))
        assertEquals("b", SelectionRules.nextDevice(devices, preferred = "gone")?.serialNumber)
    }

    @Test
    fun `with nothing usable, an unusable device is still shown rather than none`() {
        val devices = listOf(device("offline", DeviceState.OFFLINE))
        assertEquals("offline", SelectionRules.nextDevice(devices, preferred = null)?.serialNumber)
    }

    @Test
    fun `no devices, no selection`() {
        assertNull(SelectionRules.nextDevice(emptyList(), preferred = "a"))
    }

    @Test
    fun `a kept app survives a refresh of the same device`() {
        val app = SelectionRules.nextApp(current = "com.other", projectApp = "com.project", keep = true)
        assertEquals("com.other", app)
    }

    @Test
    fun `a new device selects the project's app`() {
        val app = SelectionRules.nextApp(current = "com.other", projectApp = "com.project", keep = false)
        assertEquals("com.project", app)
    }

    @Test
    fun `without a project app, the app already chosen stays`() {
        assertEquals("com.typed", SelectionRules.nextApp(current = "com.typed", projectApp = null, keep = false))
    }

    @Test
    fun `a reopened project with no app from Gradle gets the app it had last time`() {
        val app = SelectionRules.nextApp(current = null, projectApp = null, keep = false, remembered = "com.last")
        assertEquals("com.last", app)
    }

    @Test
    fun `the app remembered from last time never overrides the project's or the one chosen now`() {
        assertEquals(
            "com.project",
            SelectionRules.nextApp(current = null, projectApp = "com.project", keep = false, remembered = "com.last"),
        )
        assertEquals(
            "com.typed",
            SelectionRules.nextApp(current = "com.typed", projectApp = null, keep = false, remembered = "com.last"),
        )
    }

    @Test
    fun `an app restored from memory is provisional until something else chooses`() {
        // Reopened, the device arriving before sync ends: the remembered app, provisionally.
        val restored = SelectionRules.nextApp(current = null, projectApp = null, keep = false, remembered = "com.last")
        assertTrue(SelectionRules.restoredFromMemory(restored, current = null, projectApp = null, wasRestored = false))
        // A refresh while Gradle still names none keeps it, still provisional.
        assertTrue(SelectionRules.restoredFromMemory("com.last", "com.last", projectApp = null, wasRestored = true))
        // The project's app, or one chosen before, is not.
        assertFalse(SelectionRules.restoredFromMemory("com.project", "com.last", "com.project", wasRestored = true))
        assertFalse(SelectionRules.restoredFromMemory("com.typed", "com.typed", projectApp = null, wasRestored = false))
        assertFalse(SelectionRules.restoredFromMemory(null, current = null, projectApp = null, wasRestored = true))
    }

    @Test
    fun `sync ending reads the apps again for a restored app, as for none`() {
        assertTrue(SelectionRules.reloadWhenSynced(app = null, restoredFromMemory = false))
        assertTrue(SelectionRules.reloadWhenSynced(app = "com.last", restoredFromMemory = true))
        assertFalse(SelectionRules.reloadWhenSynced(app = "com.typed", restoredFromMemory = false))
    }

    @Test
    fun `the project's app replaces a restored one, even on a refresh of the same device`() {
        val app = SelectionRules.nextApp(
            current = "com.last",
            projectApp = "com.project",
            keep = true,
            remembered = "com.last",
            provisional = true,
        )
        assertEquals("com.project", app)
        assertEquals(
            "com.last",
            SelectionRules.nextApp(current = "com.last", projectApp = null, keep = true, provisional = true),
        )
    }

    @Test
    fun `re-reading the same device's apps does not announce the same app again`() {
        assertEquals(
            setOf(SpockSelection.Change.APPS),
            SelectionRules.afterAppsRead(current = "com.app", next = "com.app", sameDevice = true),
        )
    }

    @Test
    fun `the same app on another device is announced, its state there is different`() {
        assertEquals(
            setOf(SpockSelection.Change.APPS, SpockSelection.Change.APP),
            SelectionRules.afterAppsRead(current = "com.app", next = "com.app", sameDevice = false),
        )
    }

    @Test
    fun `a newly chosen app is announced`() {
        assertEquals(
            setOf(SpockSelection.Change.APPS, SpockSelection.Change.APP),
            SelectionRules.afterAppsRead(current = null, next = "com.app", sameDevice = true),
        )
    }

    @Test
    fun `no app chosen announces only the list`() {
        assertEquals(
            setOf(SpockSelection.Change.APPS),
            SelectionRules.afterAppsRead(current = null, next = null, sameDevice = false),
        )
    }
}
