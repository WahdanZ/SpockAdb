package spock.adb.assistant

/**
 * What the conversation looks like on screen, and the rule that keeps it bounded.
 *
 * Separate from the panel and free of Swing, so the bound, the trimming and the rendering are
 * testable without an IDE — the transcript is where a leak would show up first, since a long
 * agent session appends a line per tool call.
 */
class AssistantTranscript(private val capacity: Int = DEFAULT_CAPACITY) {

    enum class Kind { USER, ASSISTANT, TOOL, ERROR, NOTE }

    data class Entry(val kind: Kind, val text: String)

    private val entries = ArrayDeque<Entry>()

    fun add(kind: Kind, text: String) {
        entries.addLast(Entry(kind, text))
        while (entries.size > capacity) entries.removeFirst()
    }

    fun all(): List<Entry> = entries.toList()

    fun isEmpty(): Boolean = entries.isEmpty()

    fun clear() = entries.clear()

    /** The whole conversation as text, which is also what Copy Transcript hands over. */
    fun render(): String = entries.joinToString("\n\n") { render(it) }

    companion object {
        /**
         * Bounded because an agent session appends a line per tool call, and 25 iterations of
         * several calls each adds up faster than a chat does. High enough that a real session
         * is never truncated; low enough that a day of them cannot grow the heap.
         */
        const val DEFAULT_CAPACITY = 500

        fun render(entry: Entry): String = prefix(entry.kind) + entry.text

        /**
         * Prefixes rather than colours or fonts.
         *
         * They survive Copy Transcript into a bug report, where styling does not, and they keep
         * the transcript readable in a narrow docked tool window without a second column.
         */
        fun prefix(kind: Kind): String = when (kind) {
            Kind.USER -> "You:  "
            Kind.ASSISTANT -> ""
            Kind.TOOL -> "  ⚙ "
            Kind.ERROR -> "  ✗ "
            Kind.NOTE -> "  · "
        }
    }
}
