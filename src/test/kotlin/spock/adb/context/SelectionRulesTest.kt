package spock.adb.context

import com.android.ddmlib.IDevice
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
