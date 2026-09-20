package spock.adb.logcat

/**
 * The lines that were really one log statement.
 *
 * logcat has no multi-line record: an HTTP interceptor printing a JSON body, an exception and
 * its frames, a tombstone — each arrives as one record per line, and selecting any of them used
 * to show that line alone. Which is the moment the panel stops being useful: a developer
 * clicking `"count": 12,` wants the body it belongs to, not that fragment.
 *
 * Two shapes, in order of confidence:
 *
 *  - a **stack trace**, recognised by its own structure ([LogcatStackTrace]) and therefore
 *    grouped however long it took to print;
 *  - a **burst** — consecutive records from the same process, thread and tag, each within
 *    [BURST_GAP_MS] of the one before. That is what a single `Log.d` call with embedded
 *    newlines looks like by the time it reaches here.
 *
 * The gap is what keeps a chatty tag from collapsing into one enormous group: two bursts from
 * the same tag a second apart stay two groups. Pure and index-based, so both rules are testable
 * against recorded output.
 */
object LogcatGroup {

    /** Consecutive lines further apart than this were two statements, not one. */
    private const val BURST_GAP_MS = 400L

    /** A burst cannot be longer than this, whatever the timestamps say. */
    private const val MAX_BURST = 500

    enum class Kind(val label: String) {
        SINGLE("Log details"),
        BURST("Log block"),
        STACK_TRACE("Stack trace"),

        /** Not discovered but chosen: whatever rows the developer highlighted. */
        SELECTION("Selected lines"),
    }

    data class Group(val entries: List<LogcatEntry>, val kind: Kind) {
        val isMultiLine: Boolean get() = entries.size > 1

        /** The messages alone — a trace or a JSON body, readable and still linkable by an IDE. */
        fun render(): String = entries.joinToString("\n") { it.message }

        /** The records as the device sent them, for a bug report. */
        fun renderRaw(): String = entries.joinToString("\n") { it.raw }
    }

    /** Rows the developer highlighted, in view order. */
    fun ofSelection(selected: List<LogcatEntry>) = Group(selected, Kind.SELECTION)

    fun at(entries: List<LogcatEntry>, index: Int): Group {
        if (index !in entries.indices) return Group(emptyList(), Kind.SINGLE)

        val trace = LogcatStackTrace.at(entries, index)
        if (trace.size > 1) return Group(trace, Kind.STACK_TRACE)

        val burst = burstAt(entries, index)
        return when {
            burst.size > 1 -> Group(burst, Kind.BURST)
            else -> Group(listOf(entries[index]), Kind.SINGLE)
        }
    }

    private fun burstAt(entries: List<LogcatEntry>, index: Int): List<LogcatEntry> {
        val anchor = entries[index]
        // An unparsed line — a banner, a separator — has no fields to group on.
        if (anchor.timestamp.isEmpty()) return listOf(anchor)

        var start = index
        while (start > 0 && index - start < MAX_BURST && continues(entries[start - 1], entries[start])) start--

        var end = index
        while (end + 1 < entries.size && end - start < MAX_BURST && continues(entries[end], entries[end + 1])) end++

        return entries.subList(start, end + 1)
    }

    /**
     * [next] continues [previous]: same writer, near enough in time to be the same statement.
     *
     * Public because the renderers need the same answer. A continuation line repeats its
     * statement's timestamp and tag on every line — a twelve-line JSON body carrying twelve
     * copies of `17:01:18.639  ApiClient` — and those columns are exactly where the eye lands,
     * so the body itself is the last thing read. Both views blank them for a continuation, and
     * both must agree with what the details pane calls a block.
     */
    fun continues(previous: LogcatEntry, next: LogcatEntry): Boolean =
        previous.pid == next.pid &&
            previous.tid == next.tid &&
            previous.tag == next.tag &&
            previous.tag.isNotEmpty() &&
            gapMs(previous.timestamp, next.timestamp)?.let { it in 0..BURST_GAP_MS } == true

    /**
     * Milliseconds between two `MM-DD HH:MM:SS.mmm` stamps, or null when either cannot be read.
     *
     * The date is deliberately ignored and a negative result rejected by the caller: across
     * midnight the arithmetic is wrong, and the right answer there is "not the same statement"
     * rather than a wrong number.
     */
    private fun gapMs(previous: String, next: String): Long? {
        val a = millisOfDay(previous) ?: return null
        val b = millisOfDay(next) ?: return null
        return b - a
    }

    private fun millisOfDay(timestamp: String): Long? {
        val time = timestamp.substringAfter(' ', "").takeIf { it.isNotEmpty() } ?: return null
        val parts = time.split(':', '.')
        if (parts.size != PART_COUNT) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        return ((numbers[HOURS] * MINUTES_PER_HOUR + numbers[MINUTES]) * SECONDS_PER_MINUTE + numbers[SECONDS]) *
            MILLIS_PER_SECOND + numbers[MILLIS]
    }

    private const val PART_COUNT = 4
    private const val HOURS = 0
    private const val MINUTES = 1
    private const val SECONDS = 2
    private const val MILLIS = 3
    private const val MINUTES_PER_HOUR = 60
    private const val SECONDS_PER_MINUTE = 60
    private const val MILLIS_PER_SECOND = 1000
}
