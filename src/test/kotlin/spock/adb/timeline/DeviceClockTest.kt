package spock.adb.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DeviceClockTest {

    private fun utc(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `a device two hours ahead and 1,5 s fast lands on the host clock`() {
        // Host: 12:00:00.000 UTC. The device is in UTC+2 and its clock runs 1.5 s fast, so the marker
        // written then was stamped 14:00:01.500.
        val host = utc("2026-09-26T12:00:00")
        val clock = DeviceClock.sync("09-26 14:00:01.500", host)!!

        // A line the device stamped 3.25 s later happened 3.25 s later on the host.
        assertEquals(host + 3_250, clock.toHostMillis("09-26 14:00:04.750", host + 4_000))
    }

    @Test
    fun `a line from last year read after midnight keeps last year`() {
        val host = utc("2027-01-01T00:00:02")
        val clock = DeviceClock.sync("01-01 00:00:02.000", host)!!

        assertEquals(host - 4_000, clock.toHostMillis("12-31 23:59:58.000", host))
    }

    @Test
    fun `a device whose year is wrong still converts`() {
        val host = utc("2026-12-31T23:59:59")
        // The device already thinks it is the new year.
        val clock = DeviceClock.sync("01-01 00:00:01.000", host)!!

        assertEquals(host + 1_000, clock.toHostMillis("01-01 00:00:02.000", host))
    }

    @Test
    fun `anything that is not a threadtime stamp is refused`() {
        assertNull(DeviceClock.sync("", 0))
        assertNull(DeviceClock.sync("--------- beginning of main", 0))
        val clock = DeviceClock.sync("09-26 14:00:01.500", utc("2026-09-26T12:00:00"))!!
        assertNull(clock.toHostMillis("nonsense", 0))
    }
}
