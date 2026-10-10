package spock.adb.flutter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class DeviceTimeTest {

    private val berlin = ZoneOffset.ofHours(2)

    @Test
    fun `the sample with the smallest round trip wins, and the first is dropped even when fastest`() {
        // Device epoch ms are the host's middle plus a 1 s lead, so each sample says -1 s… except
        // that the slow ones are off by how much later the device answered.
        val samples = listOf(
            sample(sent = 0, received = 10, deviceMs = 1_000 + 5), // warm-up, dropped
            sample(sent = 100, received = 541, deviceMs = 100 + 1_000 + 300), // 441 ms round trip
            sample(sent = 600, received = 694, deviceMs = 647 + 1_000), // 94 ms: this one
            sample(sent = 700, received = 900, deviceMs = 800 + 1_000 + 40),
            sample(sent = 1_000, received = 1_150, deviceMs = 1_075 + 1_000 - 30),
        )

        val time = checkNotNull(DeviceTime.fromSamples(samples))

        assertEquals(1_000, time.epochOffsetMs)
        assertEquals(47, time.uncertaintyMs)
        assertEquals(berlin, time.zone)
        assertTrue(time.millisecondPrecision)
        assertNull(time.note)
    }

    @Test
    fun `epoch ms on the device's clock move onto the host's`() {
        val time = DeviceTime(epochOffsetMs = -1_000, uncertaintyMs = 50, zone = berlin)

        assertEquals(1_727_870_588_412, time.epochToHost(1_727_870_587_412))
    }

    @Test
    fun `a logcat stamp is device local time, so the zone comes off it`() {
        val time = DeviceTime(epochOffsetMs = 0, uncertaintyMs = 50, zone = berlin)
        val hostNow = Instant.parse("2026-10-02T12:00:10Z").toEpochMilli()

        // 14:00:00 in Berlin (+0200) is 12:00:00 UTC — not 14:00:00 UTC, two hours later.
        val epoch = time.logcatToEpoch("10-02 14:00:00.250", hostNow)

        assertEquals(Instant.parse("2026-10-02T12:00:00.250Z").toEpochMilli(), epoch)
    }

    @Test
    fun `a stamp from the 31st read after midnight is last year's`() {
        val time = DeviceTime(epochOffsetMs = 0, uncertaintyMs = 50, zone = ZoneOffset.UTC)
        val hostNow = Instant.parse("2027-01-01T00:00:05Z").toEpochMilli()

        val epoch = time.logcatToEpoch("12-31 23:59:58.000", hostNow)

        assertEquals(Instant.parse("2026-12-31T23:59:58Z").toEpochMilli(), epoch)
    }

    @Test
    fun `a line that is not a stamp is not read as one`() {
        val time = DeviceTime(epochOffsetMs = 0, uncertaintyMs = 50, zone = berlin)

        assertNull(time.logcatToEpoch("not a stamp", 0))
    }

    @Test
    fun `date output is read with its zone, and anything else is refused`() {
        assertEquals(
            DeviceTime.Reading(1_727_870_587_412, berlin, true),
            DeviceTime.parse("1727870587412 +0200\n"),
        )
        assertEquals(ZoneOffset.ofHoursMinutes(-5, -30), DeviceTime.parse("1727870587412 -0530")?.zone)
        assertEquals(DeviceTime.Reading(1_727_870_587_500, berlin, false), DeviceTime.parse("1727870587 +0200"))
        assertNull(DeviceTime.parse("1727870587%3N +0200"), "a date that printed %3N literally")
        assertNull(DeviceTime.parse("date: bad format"))
        assertNull(DeviceTime.parse(""))
        assertNull(DeviceTime.parse("1727870587412"), "no zone")
    }

    @Test
    fun `a date without milliseconds falls back to whole seconds, known to a second, and says so`() {
        var now = 0L
        val asked = mutableListOf<String>()
        val sampler = DeviceTimeSampler(
            run = { command ->
                asked += command
                now += 20
                if (command == DeviceTime.COMMAND) "1727870587%3N +0200" else "1727870587 +0200"
            },
            clock = { now },
        )

        val time = checkNotNull(sampler.sample())

        val expected = List(DeviceTime.SAMPLES) { DeviceTime.COMMAND } +
            List(DeviceTime.SAMPLES) { DeviceTime.SECONDS_COMMAND }
        assertEquals(expected, asked)
        assertFalse(time.millisecondPrecision)
        assertEquals(DeviceTime.SECOND_PRECISION_UNCERTAINTY_MS, time.uncertaintyMs)
        assertEquals(berlin, time.zone)
        assertNotNull(time.note)
    }

    @Test
    fun `a device that answers neither has no clock`() {
        assertNull(DeviceTimeSampler(run = { "" }, clock = { 0 }).sample())
    }

    @Test
    fun `the slot is measured once and read with a bound`() {
        val slot = DeviceTimeSlot()

        assertTrue(slot.claim())
        assertFalse(slot.claim())
        assertNull(slot.await(10), "not measured yet: the wait ends empty")
        assertFalse(slot.done)
        val time = DeviceTime(0, 1, berlin)
        slot.set(time)
        assertEquals(time, slot.current)
        assertEquals(time, slot.await(10))
        assertTrue(slot.done)
    }

    /** Times from [BASE], so the device's answer has an epoch's thirteen digits. */
    private fun sample(sent: Long, received: Long, deviceMs: Long) =
        DeviceTime.Sample(BASE + sent, BASE + received, "${BASE + deviceMs} +0200")

    private companion object {
        const val BASE = 1_727_870_587_000L
    }

    @Test
    fun `a line the shell printed before the answer is not the answer`() {
        assertEquals(
            DeviceTime.Reading(1_727_870_587_412, berlin, true),
            DeviceTime.parse("WARNING: linker: unused DT entry\n1727870587412 +0200\n\n"),
        )
    }
}
