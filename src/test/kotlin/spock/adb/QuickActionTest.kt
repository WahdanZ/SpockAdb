package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a pin list stored by the old Device tab means to the Spock Actions popup. */
class QuickActionTest {

    @Test
    fun `stored names that no longer exist cost nobody the rest of their pins`() {
        val read = QuickAction.read(listOf("RESTART_APP", "GONE_SINCE", "FORCE_STOP"))
        assertEquals(listOf(QuickAction.RESTART_APP, QuickAction.FORCE_STOP), read)
    }

    @Test
    fun `the stored order is kept, and an action is pinned once`() {
        val read = QuickAction.read(listOf("CLEAR_DATA", "RESTART_APP", "CLEAR_DATA"))
        assertEquals(listOf(QuickAction.CLEAR_DATA, QuickAction.RESTART_APP), read)
    }

    @Test
    fun `old pins become the registered actions, and permission buttons with none are dropped`() {
        val ids = QuickAction.actionIds(listOf("RESTART_APP", "GRANT_ALL_PERMISSIONS", "UNINSTALL"))
        assertEquals(
            listOf("spock.adb.actions.RestartAppAction", "spock.adb.actions.UninstallAppAction"),
            ids,
        )
    }
}
