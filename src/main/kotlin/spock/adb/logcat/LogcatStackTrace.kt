package spock.adb.logcat

/**
 * Finds the whole exception report a line belongs to.
 *
 * logcat has no notion of a multi-line record: an exception arrives as a header line followed by
 * one record per frame, each with its own timestamp. Selecting any of them and being shown only
 * that line is the single most common reason to leave this panel for `adb logcat` in a terminal,
 * so the details view and the AI context both reassemble the trace from the lines around it.
 *
 * Pure and index-based so it can be tested against recorded output.
 */
object LogcatStackTrace {

    /** Frames far enough from a header are a different trace, not a long one. */
    private const val MAX_LOOKBACK = 200
    private const val MAX_LENGTH = 400

    /**
     * The contiguous run of lines forming the trace that [index] is part of, or an empty list
     * when that line is not part of one.
     */
    fun at(entries: List<LogcatEntry>, index: Int): List<LogcatEntry> {
        if (index !in entries.indices) return emptyList()
        val anchor = entries[index]
        if (!LogcatSignals.isStackFrame(anchor) && !LogcatSignals.isExceptionHeader(anchor)) return emptyList()

        val start = headerIndex(entries, index) ?: index
        var end = index
        while (end + 1 < entries.size && end - start < MAX_LENGTH && continues(entries[start], entries[end + 1])) {
            end++
        }
        return entries.subList(start, end + 1)
    }

    /**
     * Walks back from [index] to the header the frames belong to.
     *
     * Null when there is none within [MAX_LOOKBACK] — orphan frames scrolled in after the
     * header was dropped from the buffer, which is worth showing as-is rather than pretending
     * the run above them is one trace.
     */
    fun headerIndex(entries: List<LogcatEntry>, index: Int): Int? {
        var i = index
        var steps = 0
        while (i >= 0 && steps <= MAX_LOOKBACK) {
            val entry = entries[i]
            if (LogcatSignals.isExceptionHeader(entry)) return i
            if (i != index && !LogcatSignals.isStackFrame(entry)) return null
            i--
            steps++
        }
        return null
    }

    /** Frames belong to the header that started the run: same process, same tag. */
    private fun continues(header: LogcatEntry, candidate: LogcatEntry): Boolean =
        candidate.pid == header.pid &&
            candidate.tag.equals(header.tag, ignoreCase = true) &&
            (LogcatSignals.isStackFrame(candidate) || LogcatSignals.isExceptionHeader(candidate))

    fun render(trace: List<LogcatEntry>): String = trace.joinToString("\n") { it.message }
}
