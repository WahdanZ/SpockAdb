package spock.adb.logcat

/**
 * Everything the view is narrowed by, kept pure so it can be tested directly.
 *
 * Four independent axes — [scope], [intent], [minLevel] and the text [query] — combined with
 * AND. They are separate fields rather than one preset because a developer chasing a crash in
 * their own app and a developer chasing an ANR anywhere on the device are asking two different
 * questions, and a single list of presets forces them to answer both at once.
 *
 * Matching happens on every incoming line on a device with a busy log, so the predicate is
 * cheap and ordered cheapest-first: an ordinal comparison, a set lookup, and at most one
 * substring or regex test.
 */
data class LogcatFilter(
    val scope: LogcatScope = LogcatScope.APP,
    val intent: LogcatIntent = LogcatIntent.ALL,
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val query: String = "",
    val useRegex: Boolean = false,
    /** What is known about the app's processes. See [AppProcesses]. */
    val app: AppProcesses = AppProcesses.UNKNOWN,
    val tag: String = "",
) {

    private val regex: Regex? = when {
        useRegex && query.isNotBlank() -> runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        else -> null
    }

    /** True when [query] is meant as a regex but does not compile, so the UI can say so. */
    val hasInvalidRegex: Boolean = useRegex && query.isNotBlank() && regex == null

    /** Why the scope is not showing what its name promises, or null when it is. */
    fun scopeCaveat(): String? = scope.caveat(app)

    /** False when the chosen scope could not be enforced as named. */
    val isScopeApplied: Boolean get() = scopeCaveat() == null

    fun matches(entry: LogcatEntry): Boolean {
        // A record with no message has nothing to show. The device emits them — every
        // `adb shell log` invocation ends with one — and in a list they are blank rows that
        // cost a line of screen and carry nothing. The raw record stays in the buffer.
        if (entry.message.isBlank()) return false
        if (!entry.level.isAtLeast(minLevel)) return false
        if (!scope.matches(entry, app)) return false
        if (!intent.matches(entry)) return false
        if (tag.isNotBlank() && !entry.tag.contains(tag, ignoreCase = true)) return false
        if (query.isBlank()) return true

        return when {
            // An invalid regex matches nothing rather than everything: silently showing the
            // unfiltered log would look like the filter was ignored.
            hasInvalidRegex -> false
            regex != null -> regex.containsMatchIn(entry.message) || regex.containsMatchIn(entry.tag)
            else -> entry.message.contains(query, ignoreCase = true) ||
                entry.tag.contains(query, ignoreCase = true)
        }
    }

    /** One line naming what is being shown, for the status bar and the AI context header. */
    fun describe(): String = buildString {
        append("Scope: ").append(scope.label)
        append(" · Level: ").append(minLevel.label).append('+')
        if (intent != LogcatIntent.ALL) append(" · Showing: ").append(intent.label)
        if (query.isNotBlank()) append(" · Search: ").append(query)
    }
}

/**
 * Marks the lines a developer is usually scanning for, so they can be highlighted.
 *
 * A crash is several lines — the header, then the frames — so classification is per line and
 * the UI colours each one it recognises.
 */
object LogcatHighlighter {

    fun classify(entry: LogcatEntry): Highlight = when {
        LogcatSignals.isAnr(entry) -> Highlight.ANR
        LogcatSignals.isCrash(entry) -> Highlight.CRASH
        entry.level == LogLevel.ERROR -> Highlight.ERROR
        entry.level == LogLevel.WARN -> Highlight.WARNING
        else -> Highlight.NONE
    }

    enum class Highlight { NONE, WARNING, ERROR, CRASH, ANR }
}
