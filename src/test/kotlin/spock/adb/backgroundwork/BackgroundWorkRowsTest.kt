package spock.adb.backgroundwork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spock.adb.parser.AlarmDumpParser
import spock.adb.parser.JobSchedulerDumpParser
import java.time.ZoneId

/** What the tables show, read at the width of a docked tool window. */
class BackgroundWorkRowsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/dumpsys/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    private val utc = ZoneId.of("UTC")

    @Test
    fun `a namespaced WorkManager job shows its id, not its namespace`() {
        val job = JobSchedulerDumpParser.parse(fixture("jobscheduler-api34-sample-app.txt"), SAMPLE)
            .jobs.single { it.jobId == 6 }

        val row = BackgroundWorkPanel.jobRow(job, now = 0L)

        assertEquals("6", row[0])
        assertEquals("WorkManager", row[1])
    }

    @Test
    fun `alarm tags drop the wakeup marker and the app's own package`() {
        val tags = AlarmDumpParser.parse(fixture("alarm-api34-sample-app.txt"), SAMPLE).alarms
            .map { BackgroundWorkPanel.shortTag(it) }
            .toSet()

        assertEquals(setOf("ALARM_EXACT", "ALARM_INEXACT", "ALARM_REPEATING"), tags)
    }

    @Test
    fun `times lead with the relative part, and drop the date when it is today`() {
        val alarm = AlarmDumpParser.parse(fixture("alarm-api34-sample-app.txt"), SAMPLE).alarms
            .single { it.tag!!.endsWith("ALARM_EXACT") }

        assertEquals("in 59m 40s", BackgroundWorkPanel.compactTrigger(alarm).substringBefore(" ("))

        val noon = 1_790_000_000_000L - 1_790_000_000_000L % 86_400_000L + 12 * 3_600_000L
        assertEquals("12:30", BackgroundWorkPanel.shortClock(noon + 1_800_000L, noon, utc))
        assertEquals("Sep 22 12:00", BackgroundWorkPanel.shortClock(noon + 86_400_000L, noon, utc))
    }

    private companion object {
        const val SAMPLE = "spock.adb.sample"
    }
}
