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
    fun `pins are remembered in the order they were put in`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }

        assertEquals(emptyList<QuickAction>(), service.pinnedActions())
        service.savePinnedActions(listOf(QuickAction.FORCE_STOP, QuickAction.RESTART_APP))

        assertEquals(
            listOf(QuickAction.FORCE_STOP, QuickAction.RESTART_APP),
            service.pinnedActions(),
            "the order is the point, so it cannot be stored as a set",
        )
        assertEquals(listOf("FORCE_STOP", "RESTART_APP"), service.state.pinned, "it has to survive a restart")
    }

    @Test
    fun `the proxy is remembered so it need not be retyped`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }

        assertEquals("", service.lastHttpProxy())
        service.saveHttpProxy("192.168.1.10:8888")
        assertEquals("192.168.1.10:8888", service.lastHttpProxy())
        assertEquals("192.168.1.10:8888", service.state.httpProxy, "it has to survive a restart")
    }

    @Test
    fun `every proxy set is offered again, most recent first`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }

        service.saveHttpProxy("10.0.2.2:8888")
        service.saveHttpProxy("192.168.1.10:8080")

        assertEquals(listOf("192.168.1.10:8080", "10.0.2.2:8888"), service.httpProxyHistory())
        assertEquals(
            listOf("192.168.1.10:8080", "10.0.2.2:8888"),
            service.state.httpProxyHistory,
            "the list has to survive a restart",
        )
    }

    @Test
    fun `setting one again moves it up rather than listing it twice`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }

        service.saveHttpProxy("a:1")
        service.saveHttpProxy("b:2")
        service.saveHttpProxy("a:1")

        assertEquals(listOf("a:1", "b:2"), service.httpProxyHistory())
    }

    @Test
    fun `the one proxy an upgrade brings with it becomes the first entry`() {
        // Settings written before the history existed hold a single proxy and no list.
        val service = service.apply { loadState(AppSetting(list = emptyList(), httpProxy = "10.0.2.2:8888")) }

        assertEquals(listOf("10.0.2.2:8888"), service.httpProxyHistory(), "it must not be lost to the upgrade")
    }

    @Test
    fun `forgetting the list leaves nothing to offer`() {
        val service = service.apply { loadState(AppSetting(list = emptyList())) }
        service.saveHttpProxy("a:1")

        service.clearHttpProxyHistory()

        assertEquals(emptyList<String>(), service.httpProxyHistory())
        assertEquals("", service.lastHttpProxy(), "the seed must not bring the forgotten one back")
    }

    @Test
    fun `the list is a working set, not a log of everything ever typed`() {
        val history = (1..MAX_REMEMBERED_PROXIES + 3)
            .fold(emptyList<String>()) { acc, port -> proxyHistoryWith(acc, "10.0.0.1:$port") }

        assertEquals(MAX_REMEMBERED_PROXIES, history.size)
        assertEquals("10.0.0.1:${MAX_REMEMBERED_PROXIES + 3}", history.first(), "the newest is kept")
        assertEquals("10.0.0.1:4", history.last(), "the oldest falls off the end")
    }

    @Test
    fun `a blank proxy is not remembered`() {
        assertEquals(listOf("a:1"), proxyHistoryWith(listOf("a:1"), "   "))
    }
}
