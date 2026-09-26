package spock.adb.timeline

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bounded, chronological store of [TimelineEvent]s.
 *
 * Bounded because it records for as long as the project is open: a device that logs a warning a
 * second would otherwise grow the heap all day. Once full, the oldest events go first, and
 * [dropped] counts them so the panel can say the history is not complete.
 *
 * Kept in time order on insert rather than append order. Device log lines reach it later than
 * the moment they describe, so an action recorded on the host can arrive after a line logged
 * before it; sorting on read would re-sort thousands of events for every repaint.
 */
class DebugTimeline(capacity: Int = DEFAULT_CAPACITY) {

    var capacity: Int = capacity.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
        set(value) {
            field = value.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
            synchronized(this) { trim() }
        }

    private val events = ArrayList<TimelineEvent>()
    private var nextId = 1L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** How many events were dropped to stay within [capacity] since the last [clear]. */
    @Volatile
    var dropped: Long = 0
        private set

    /** Adds [event], stamped with its id, and tells the listeners. Safe from any thread. */
    fun record(event: TimelineEvent): TimelineEvent {
        val stored = synchronized(this) {
            val withId = event.copy(id = nextId++)
            events.add(insertionPoint(withId.timeMs), withId)
            trim()
            withId
        }
        listeners.forEach { it() }
        return stored
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<TimelineEvent> = events.toList()

    /** Oldest first. */
    @Synchronized
    fun query(filter: TimelineFilter): List<TimelineEvent> = events.filter(filter::matches)

    @Synchronized
    fun size(): Int = events.size

    fun clear() {
        synchronized(this) {
            events.clear()
            dropped = 0
        }
        listeners.forEach { it() }
    }

    /** Called on the recording thread after every change; the listener finds its own way to the EDT. */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** After every event at or before [timeMs]: almost always the end, so search from there. */
    private fun insertionPoint(timeMs: Long): Int {
        var index = events.size
        while (index > 0 && events[index - 1].timeMs > timeMs) index--
        return index
    }

    private fun trim() {
        val excess = events.size - capacity
        if (excess <= 0) return
        events.subList(0, excess).clear()
        dropped += excess
    }

    companion object {
        const val DEFAULT_CAPACITY = 5_000
        const val MIN_CAPACITY = 100
        const val MAX_CAPACITY = 50_000
    }
}
