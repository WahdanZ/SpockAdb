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
    fun `a bucket the device did not take is explained`() {
        assertNull(DeviceConditionShell.bucketRefusal("a.b", StandbyBucket.RARE, StandbyBucket.RARE))
        val kept = DeviceConditionShell.bucketRefusal("a.b", StandbyBucket.RARE, StandbyBucket.WORKING_SET)!!
        assertTrue(kept.startsWith("Android kept a.b in Working set rather than Rare."), kept)
        assertTrue(kept.contains("exact alarms"), kept)
    }
}
