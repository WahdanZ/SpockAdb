package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlarmDumpParserTest {

    private fun dump(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/dumpsys/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    /** Captured from an API 34 emulator, whose clock read nowRTC=1790015521698 nowELAPSED=212171703. */
    private val api34 by lazy { dump("alarm-api34.txt") }

    /** Written from AOSP's Android 9 `Alarm.dump`; no API 28 device was to hand. */
    private val api28 by lazy { dump("alarm-api28-reconstructed.txt") }

    @Test
    fun `reads every pending alarm from a real API 34 dump`() {
        val all = AlarmDumpParser.parse(api34)

        assertNull(all.problem)
        assertEquals(46, all.alarms.size)
    }

    @Test
    fun `an RTC alarm is placed on the device clock`() {
        val alarm = AlarmDumpParser.parse(api34, "com.android.settings").alarms.single()

        assertEquals("RTC_WAKEUP", alarm.type)
        assertEquals("*walarm*:com.android.settings.battery.action.PERIODIC_JOB_UPDATE", alarm.tag)
        // origWhen 1790017200000 is 21:00:00.000 on the device; whenElapsed lands 6 ms later.
        assertEquals(1_790_017_200_001L, alarm.triggerAtMillis)
        assertEquals(213_850_006L - 212_171_703L, alarm.dueInMillis)
        assertTrue(alarm.isWakeup)
        assertTrue(alarm.isExact)
        assertFalse(alarm.isRepeating)
    }

    @Test
    fun `an elapsed alarm is converted to clock time through the dump's own clock`() {
        val alarm = AlarmDumpParser.parse(api34, "com.android.providers.calendar").alarms.single()

        assertEquals("ELAPSED_WAKEUP", alarm.type)
        assertEquals(216_000_000L - 212_171_703L, alarm.dueInMillis)
        assertEquals(1_790_015_521_698L + (216_000_000L - 212_171_703L), alarm.triggerAtMillis)
        assertEquals(21_600_000L, alarm.repeatIntervalMillis)
        assertEquals("+4h30m0s0ms", alarm.window)
        assertFalse(alarm.isExact)
    }

    @Test
    fun `an alarm set to the end of time never fires`() {
        val never = AlarmDumpParser.parse(api34, "com.google.android.googlequicksearchbox").alarms
            .single { it.type == "RTC" }

        assertNull(never.triggerAtMillis)
        assertNull(never.dueInMillis)
    }

    @Test
    fun `reads the sample app's exact, inexact and repeating alarms`() {
        // Captured from the repo's own sample app after "Schedule everything".
        val alarms = AlarmDumpParser.parse(dump("alarm-api34-sample-app.txt"), "spock.adb.sample").alarms
            .associateBy { it.tag?.substringAfterLast('.') }

        assertEquals(setOf("ALARM_EXACT", "ALARM_INEXACT", "ALARM_REPEATING"), alarms.keys)
        assertTrue(alarms.getValue("ALARM_EXACT").isExact)
        assertEquals("RTC_WAKEUP", alarms.getValue("ALARM_EXACT").type)
        assertFalse(alarms.getValue("ALARM_INEXACT").isExact)
        assertEquals(1_800_000L, alarms.getValue("ALARM_REPEATING").repeatIntervalMillis)
    }

    @Test
    fun `reads the batched format of earlier releases`() {
        val alarms = AlarmDumpParser.parse(api28, "com.example.app").alarms

        assertEquals(2, alarms.size)
        val reminder = alarms.single { it.type == "RTC_WAKEUP" }
        assertEquals(1_570_183_200_000L, reminder.triggerAtMillis)
        assertEquals(20 * 60_000L, reminder.dueInMillis)
        assertEquals(86_400_000L, reminder.repeatIntervalMillis)
        assertTrue(reminder.isExact)

        val refresh = alarms.single { it.type == "ELAPSED" }
        assertEquals(1_570_182_000_000L + 3_600_000L, refresh.triggerAtMillis)
        assertEquals("+15m0s0ms", refresh.window)
        assertEquals(
            "PendingIntent{9a8b7c6: PendingIntentRecord{5d4e3f2 com.example.app broadcastIntent}}",
            refresh.target,
        )
    }

    @Test
    fun `another package's alarms are left out`() {
        assertTrue(AlarmDumpParser.parse(api28, "com.nobody.here").alarms.isEmpty())
        assertNull(AlarmDumpParser.parse(api28, "com.nobody.here").problem)
    }

    @Test
    fun `a dump with no recognisable alarms is a problem and keeps the raw text`() {
        val unknown = AlarmDumpParser.parse("Current Alarm Manager state:\n  Something new: 3\n")

        assertNotNull(unknown.problem)
        assertNotNull(unknown.raw)
    }

    @Test
    fun `a device with no alarms at all is not a problem`() {
        val none = AlarmDumpParser.parse("Current Alarm Manager state:\n  0 pending alarms:\n")

        assertNull(none.problem)
        assertTrue(none.alarms.isEmpty())
    }
}
