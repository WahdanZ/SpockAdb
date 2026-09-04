package spock.adb.logcat

/**
 * Bounded, thread-safe store of received entries.
 *
 * A busy device produces thousands of lines a minute. Keeping them all would grow the heap
 * without limit for a panel that only ever shows a screenful, so the buffer is a ring: the
 * oldest entries are dropped once it is full.
 */
class LogcatBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<LogcatEntry>(INITIAL_CAPACITY)

    @Synchronized
    fun add(entry: LogcatEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun snapshot(): List<LogcatEntry> = entries.toList()

    @Synchronized
    fun filtered(filter: LogcatFilter): List<LogcatEntry> = entries.filter(filter::matches)

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_CAPACITY = 20_000
        private const val INITIAL_CAPACITY = 1_024
    }
}
