package spock.adb.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class TimelineExportTest {

    private fun event(timeMs: Long, title: String, detail: String = "") =
        TimelineEvent(
            timeMs,
            TimelineCategory.LOG,
            TimelineSeverity.ERROR,
            title,
            detail,
            deviceSerial = "emulator-5554"
        )

    private val events = listOf(event(1_000, "a"), event(2_000, "b"), event(3_000, "c"), event(4_000, "d"))

    @Test
    fun `a range is everything between the earliest and latest selected, not just the selection`() {
        val range = TimelineExport.range(events, listOf(events[3], events[1]))

        assertEquals(listOf("b", "c", "d"), range.map { it.title })
    }

    @Test
    fun `no selection is an empty range`() {
        assertTrue(TimelineExport.range(events, emptyList()).isEmpty())
    }

    @Test
    fun `the text has a header, one line per event and the detail indented`() {
        val text = TimelineExport.format(
            listOf(event(1_234, "AndroidRuntime: FATAL EXCEPTION", "line one\nline two")),
            ZoneOffset.UTC,
        )
        val lines = text.lines()

        assertTrue(lines[0].startsWith("Spock ADB debug timeline, 1 event(s), 1970-01-01 00:00:01.234"))
        assertEquals("00:00:01.234  ERROR   Log        AndroidRuntime: FATAL EXCEPTION  [emulator-5554]", lines[1])
        assertEquals("        line one", lines[2])
        assertEquals("        line two", lines[3])
    }

    @Test
    fun `nothing to export says so`() {
        assertEquals("No timeline events.", TimelineExport.format(emptyList()))
    }
}
