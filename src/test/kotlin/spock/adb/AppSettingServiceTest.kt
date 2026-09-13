package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.premission.ListItem

/**
 * The stored action list replaces the defaults wholesale, so anything added to [SpockAction]
 * after a user's settings were first written has to be merged back in on load — otherwise
 * the settings dialog, which is built from this list, has no entry to toggle it with.
 */
class AppSettingServiceTest {

    private val service get() = AppSettingService()

    @Test
    fun `an action added after the settings were stored appears, switched on`() {
        val stored = AppSetting(
            selectedDevice = "emulator-5554",
            list = listOf(ListItem("CURRENT ACTIVITY", true)),
        )

        val service = service.apply { loadState(stored) }
        val names = service.state.list.map { it.name }

        assertTrue("HTTP PROXY" in names, "a newly added action must be merged in: $names")
        assertTrue(
            service.state.list.single { it.name == "HTTP PROXY" }.isSelected,
            "a new action defaults to shown, as a fresh install would have it",
        )
    }

    @Test
    fun `every action is present after a load, whatever was stored`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }

        assertEquals(
            SpockAction.entries.map { it.name.replace("_", " ") }.toSet(),
            service.state.list.map { it.name }.toSet(),
        )
    }

    @Test
    fun `a stored choice is not overwritten by the merge`() {
        val stored = AppSetting(list = listOf(ListItem("CURRENT ACTIVITY", false)))

        val service = service.apply { loadState(stored) }

        assertEquals(
            false,
            service.state.list.single { it.name == "CURRENT ACTIVITY" }.isSelected,
            "switching an action off must survive the merge",
        )
    }

    @Test
    fun `unknown stored entries are left alone`() {
        // Renamed or removed actions still round-trip; updateUi ignores what it cannot match.
        val stored = AppSetting(list = listOf(ListItem("SOME REMOVED ACTION", true)))

        val service = service.apply { loadState(stored) }

        assertTrue("SOME REMOVED ACTION" in service.state.list.map { it.name })
    }

    @Test
    fun `the proxy is remembered so it need not be retyped`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }

        assertEquals("", service.lastHttpProxy())
        service.saveHttpProxy("192.168.1.10:8888")
        assertEquals("192.168.1.10:8888", service.lastHttpProxy())
        assertEquals("192.168.1.10:8888", service.state.httpProxy, "it has to survive a restart")
    }
}
