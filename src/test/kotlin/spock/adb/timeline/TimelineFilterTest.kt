package spock.adb.timeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineFilterTest {

    private val warning = TimelineEvent(
        timeMs = 1_000,
        category = TimelineCategory.LOG,
        severity = TimelineSeverity.WARNING,
        title = "Payments: retrying",
        detail = "attempt 2 of 3",
    )

    @Test
    fun `the default filter lets everything through`() {
        assertTrue(TimelineFilter().matches(warning))
    }

    @Test
    fun `category and severity narrow it`() {
        assertFalse(TimelineFilter(categories = setOf(TimelineCategory.MCP)).matches(warning))
        assertTrue(TimelineFilter(minSeverity = TimelineSeverity.WARNING).matches(warning))
        assertFalse(TimelineFilter(minSeverity = TimelineSeverity.ERROR).matches(warning))
    }

    @Test
    fun `the query searches the title and the detail, ignoring case`() {
        assertTrue(TimelineFilter(query = "payments").matches(warning))
        assertTrue(TimelineFilter(query = "ATTEMPT 2").matches(warning))
        assertFalse(TimelineFilter(query = "crash").matches(warning))
    }

    @Test
    fun `time bounds are inclusive`() {
        assertTrue(TimelineFilter(sinceMs = 1_000, untilMs = 1_000).matches(warning))
        assertFalse(TimelineFilter(sinceMs = 1_001).matches(warning))
        assertFalse(TimelineFilter(untilMs = 999).matches(warning))
    }
}
