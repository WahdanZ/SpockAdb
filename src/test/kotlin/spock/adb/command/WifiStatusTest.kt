package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Telling an enabled radio apart from a connected one.
 *
 * `settings get global wifi_on` answers only the first, so a device sitting on an enabled radio
 * with no network read exactly like one on the office Wi-Fi — which is the distinction a
 * developer is usually looking for when they look at all.
 */
class WifiStatusTest {

    private val connected = """
        Wifi is enabled
        Wifi scanning is always available
        ==== Primary ClientModeManager instance ====
        Wifi is connected to "AndroidWifi"
        WifiInfo: SSID: "AndroidWifi", BSSID: 00:13:10:85:fe:01, RSSI: -50, Frequency: 2447MHz
    """.trimIndent()

    @Test
    fun `a connected device names its network`() {
        val status = WifiStatus.parse(connected)

        assertTrue(status.enabled)
        assertEquals("AndroidWifi", status.ssid, "the quotes are the device's, not part of the name")
        assertEquals("Connected (AndroidWifi)", status.describe())
    }

    @Test
    fun `an enabled radio with no network is not reported as connected`() {
        val status = WifiStatus.parse("Wifi is enabled\nWifi scanning is always available")

        assertTrue(status.enabled)
        assertNull(status.ssid)
        assertEquals("On", status.describe())
    }

    @Test
    fun `a disabled radio is off`() {
        val status = WifiStatus.parse("Wifi is disabled\nWifi scanning is always available")

        assertFalse(status.enabled)
        assertNull(status.ssid)
        assertEquals("Off", status.describe())
    }

    @Test
    fun `a device that says only that it is connected is still enabled`() {
        // The enabled line is not guaranteed to come first, or at all, on every build.
        val status = WifiStatus.parse("""Wifi is connected to "Office 5G"""")

        assertTrue(status.enabled, "it cannot be connected and disabled at once")
        assertEquals("Office 5G", status.ssid)
    }

    @Test
    fun `nothing at all is off rather than a guess`() {
        val status = WifiStatus.parse("")

        assertFalse(status.enabled)
        assertNull(status.ssid)
    }
}
