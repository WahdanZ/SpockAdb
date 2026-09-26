package spock.adb.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugTimelineTest {

    private fun event(timeMs: Long, title: String = "t$timeMs") =
        TimelineEvent(timeMs, TimelineCategory.MARKER, TimelineSeverity.INFO, title)

    @Test
    fun `a line that arrives late still lands in time order`() {
        val timeline = DebugTimeline()
        timeline.record(event(100))
        timeline.record(event(300))
        // A device log line describing a moment before the action recorded at 300.
        timeline.record(event(200))

        assertEquals(listOf(100L, 200L, 300L), timeline.snapshot().map { it.timeMs })
    }

    @Test
    fun `events at the same millisecond keep the order they were recorded in`() {
        val timeline = DebugTimeline()
        timeline.record(event(100, "first"))
        timeline.record(event(100, "second"))

        assertEquals(listOf("first", "second"), timeline.snapshot().map { it.title })
        assertTrue(timeline.snapshot()[0].id < timeline.snapshot()[1].id)
    }

    @Test
    fun `once full the oldest go first and are counted`() {
        val timeline = DebugTimeline(capacity = DebugTimeline.MIN_CAPACITY)
        repeat(DebugTimeline.MIN_CAPACITY + 5) { timeline.record(event(it.toLong())) }

        assertEquals(DebugTimeline.MIN_CAPACITY, timeline.size())
        assertEquals(5L, timeline.snapshot().first().timeMs)
        assertEquals(5L, timeline.dropped)
    }

    @Test
    fun `lowering the capacity trims at once`() {
        val timeline = DebugTimeline()
        repeat(300) { timeline.record(event(it.toLong())) }
        timeline.capacity = 150

        assertEquals(150, timeline.size())
        assertEquals(150L, timeline.dropped)
    }

    @Test
    fun `clear empties it, resets the dropped count and tells listeners`() {
        val timeline = DebugTimeline(capacity = DebugTimeline.MIN_CAPACITY)
        repeat(DebugTimeline.MIN_CAPACITY + 1) { timeline.record(event(it.toLong())) }
        var told = 0
        timeline.addListener { told++ }

        timeline.clear()

        assertEquals(0, timeline.size())
        assertEquals(0L, timeline.dropped)
        assertEquals(1, told)
    }
}
