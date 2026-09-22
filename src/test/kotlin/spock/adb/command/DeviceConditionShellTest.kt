package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Outputs below were captured from an API 34 emulator. */
class DeviceConditionShellTest {

    @Test
    fun `reads the deep idle state`() {
        assertEquals("IDLE", DeviceConditionShell.parseDeepIdle("IDLE\n"))
        assertEquals("ACTIVE", DeviceConditionShell.parseDeepIdle("ACTIVE"))
        assertNull(DeviceConditionShell.parseDeepIdle("Can't find service: deviceidle"))
    }

    @Test
    fun `reads buckets by number and by name`() {
        assertEquals(StandbyBucket.RARE, DeviceConditionShell.parseBucket("40\n"))
        assertEquals(StandbyBucket.RESTRICTED, DeviceConditionShell.parseBucket("45"))
        assertEquals(StandbyBucket.WORKING_SET, DeviceConditionShell.parseBucket("working_set"))
        assertNull(DeviceConditionShell.parseBucket("Error: Unknown bucket: bogus"))
    }

    @Test
    fun `an overridden battery is recognised by its stopped updates`() {
        val overridden = """
            Current Battery Service state:
              (UPDATES STOPPED -- use 'reset' to restart)
              AC powered: false
              USB powered: false
              Wireless powered: false
              Dock powered: false
              level: 10
        """.trimIndent()
        val (stopped, powered, level) = DeviceConditionShell.parseBattery(overridden)
        assertTrue(stopped)
        assertEquals(false, powered)
        assertEquals(10, level)

        val real = "Current Battery Service state:\n  AC powered: true\n  USB powered: false\n  level: 100"
        val (realStopped, realPowered, _) = DeviceConditionShell.parseBattery(real)
        assertFalse(realStopped)
        assertEquals(true, realPowered)
    }

    @Test
    fun `commands quote the package and refuse what is not one`() {
        assertEquals(
            "am set-standby-bucket 'com.example.app' rare",
            DeviceConditionShell.setBucketCommand("com.example.app", StandbyBucket.RARE)
        )
        assertThrows<IllegalArgumentException> { DeviceConditionShell.getBucketCommand("a;reboot") }
    }

    @Test
    fun `features are refused on releases that do not have them`() {
        assertNotNull(DeviceConditionShell.bucketUnavailableReason(27, StandbyBucket.RARE))
        assertNull(DeviceConditionShell.bucketUnavailableReason(28, StandbyBucket.RARE))
        assertNotNull(DeviceConditionShell.bucketUnavailableReason(29, StandbyBucket.RESTRICTED))
        assertNull(DeviceConditionShell.bucketUnavailableReason(30, StandbyBucket.RESTRICTED))
        assertNotNull(DeviceConditionShell.dozeUnavailableReason(22))
        assertNull(DeviceConditionShell.dozeUnavailableReason(23))
    }

    @Test
    fun `force idle succeeds only when the device says it went idle`() {
        assertNull(DeviceConditionShell.forceIdleFailure("Now forced in to deep idle mode\n"))
        assertEquals(
            "Unable to go deep idle; not enabled",
            DeviceConditionShell.forceIdleFailure("Unable to go deep idle; not enabled")
        )
        assertNotNull(DeviceConditionShell.forceIdleFailure(""))
    }

    @Test
    fun `a battery level command is built only for a percentage`() {
        assertEquals("dumpsys battery set level 5", DeviceConditionShell.setLevelCommand(5))
        assertEquals("dumpsys battery set level 100", DeviceConditionShell.setLevelCommand(100))
        assertThrows<IllegalArgumentException> { DeviceConditionShell.setLevelCommand(101) }
        assertThrows<IllegalArgumentException> { DeviceConditionShell.setLevelCommand(-1) }
    }

    @Test
    fun `a level the device did not take is explained`() {
        assertNull(DeviceConditionShell.levelRefusal(20, 20))
        assertTrue(DeviceConditionShell.levelRefusal(20, 87)!!.contains("still reports 87%"))
        assertNotNull(DeviceConditionShell.levelRefusal(20, null))
    }

    @Test
    fun `the battery override is refused before Android 6`() {
        assertNotNull(DeviceConditionShell.batteryUnavailableReason(22))
        assertNull(DeviceConditionShell.batteryUnavailableReason(23))
        assertNull(DeviceConditionShell.batteryUnavailableReason(null))
    }

    @Test
    fun `every preset is a percentage`() {
        assertEquals(listOf(5, 20, 50, 100), BatteryLevelPreset.entries.map { it.level })
        val percentages = DeviceConditionShell.MIN_LEVEL..DeviceConditionShell.MAX_LEVEL
        assertTrue(BatteryLevelPreset.entries.all { it.level in percentages })
    }

    @Test
    fun `each charger is read separately, so USB can stay on while AC is off`() {
        val dump = """
            Current Battery Service state:
              (UPDATES STOPPED -- use 'reset' to restart)
              AC powered: false
              USB powered: true
              Wireless powered: false
              Dock powered: false
              level: 20
        """.trimIndent()

        val chargers = DeviceConditionShell.parseChargers(dump)

        assertEquals(false, chargers[ChargerSource.AC])
        assertEquals(true, chargers[ChargerSource.USB])
        assertEquals(false, chargers[ChargerSource.WIRELESS])
        // Dock is reported by the device but cannot be set, so it is not a ChargerSource.
        assertEquals(3, chargers.size)
    }

    @Test
    fun `charger commands use the dumpsys argument and a 1 or 0`() {
        assertEquals("dumpsys battery set usb 1", DeviceConditionShell.setChargerCommand(ChargerSource.USB, true))
        assertEquals("dumpsys battery set ac 0", DeviceConditionShell.setChargerCommand(ChargerSource.AC, false))
    }

    @Test
    fun `a bucket the device did not take is explained`() {
        assertNull(DeviceConditionShell.bucketRefusal("a.b", StandbyBucket.RARE, StandbyBucket.RARE))
        val kept = DeviceConditionShell.bucketRefusal("a.b", StandbyBucket.RARE, StandbyBucket.WORKING_SET)!!
        assertTrue(kept.startsWith("Android kept a.b in Working set rather than Rare."), kept)
        assertTrue(kept.contains("exact alarms"), kept)
    }
}
