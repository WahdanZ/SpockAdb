package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * These enums translate raw `settings get` / `getprop` output into plugin state. They all
 * fall back to DISABLED for anything unrecognised, which matters because a device that does
 * not have the setting at all answers "null".
 */
class DeviceStateParsingTest {

    @Test
    fun `network state reads the enabled and disabled flags`() {
        assertEquals(NetworkState.ENABLED, NetworkState.getState("1"))
        assertEquals(NetworkState.DISABLED, NetworkState.getState("0"))
    }

    @Test
    fun `network state falls back to disabled for unset or unexpected output`() {
        assertEquals(NetworkState.DISABLED, NetworkState.getState("null"))
        assertEquals(NetworkState.DISABLED, NetworkState.getState(""))
    }

    @Test
    fun `show taps state reads the enabled and disabled flags`() {
        assertEquals(ShowTapsState.ENABLED, ShowTapsState.getState("1"))
        assertEquals(ShowTapsState.DISABLED, ShowTapsState.getState("0"))
        assertEquals(ShowTapsState.DISABLED, ShowTapsState.getState("null"))
    }

    @Test
    fun `dont keep activities state reads the enabled and disabled flags`() {
        assertEquals(DontKeepActivitiesState.ENABLED, DontKeepActivitiesState.getState("1"))
        assertEquals(DontKeepActivitiesState.DISABLED, DontKeepActivitiesState.getState("0"))
        assertEquals(DontKeepActivitiesState.DISABLED, DontKeepActivitiesState.getState("null"))
    }

    @Test
    fun `network enum maps each network to its settings and svc identifiers`() {
        assertEquals("wifi_on", Network.WIFI.networkSettingIdentifier)
        assertEquals("wifi", Network.WIFI.networkChangeIdentifier)
        assertEquals("mobile_data", Network.MOBILE.networkSettingIdentifier)
        assertEquals("data", Network.MOBILE.networkChangeIdentifier)
    }
}
