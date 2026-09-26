package spock.adb.timeline

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Turns part of the timeline into text a developer can paste into a bug report. */
object TimelineExport {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * Every event of [events] from the earliest to the latest of [selected], inclusive.
     *
     * A time range rather than the selected rows themselves: what matters in a report is what
     * happened between two moments, including the events the developer did not click on.
     */
    fun range(events: List<TimelineEvent>, selected: List<TimelineEvent>): List<TimelineEvent> {
        if (selected.isEmpty()) return emptyList()
        val from = selected.minOf { it.timeMs }
        val to = selected.maxOf { it.timeMs }
        return events.filter { it.timeMs in from..to }
    }

    fun clock(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        CLOCK.format(Instant.ofEpochMilli(timeMs).atZone(zone))

    /** One line per event, its detail indented beneath it. */
    fun format(events: List<TimelineEvent>, zone: ZoneId = ZoneId.systemDefault()): String {
        if (events.isEmpty()) return "No timeline events."
        return formatLines(events, zone)
    }

    private fun formatLines(events: List<TimelineEvent>, zone: ZoneId): String = buildString {
        val first = FULL.format(Instant.ofEpochMilli(events.first().timeMs).atZone(zone))
        val last = FULL.format(Instant.ofEpochMilli(events.last().timeMs).atZone(zone))
        append("Spock ADB debug timeline, ").append(events.size).append(" event(s), ")
        append(first).append(" to ").append(last).append(" (").append(zone.id).append(")\n")
        events.forEach { event ->
            append(clock(event.timeMs, zone)).append("  ")
            append(event.severity.name.padEnd(SEVERITY_WIDTH)).append(' ')
            append(event.category.label.padEnd(CATEGORY_WIDTH)).append(' ')
            append(event.title)
            event.deviceSerial?.let { append("  [").append(it).append(']') }
            append('\n')
            event.detail.takeIf { it.isNotBlank() && it != event.title }?.lineSequence()?.forEach { line ->
                append(DETAIL_INDENT).append(line).append('\n')
            }
        }
    }

    private const val SEVERITY_WIDTH = 7
    private const val CATEGORY_WIDTH = 10
    private const val DETAIL_INDENT = "        "
}
