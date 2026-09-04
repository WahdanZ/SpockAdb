package spock.adb.logcat

/**
 * Filtering rules for the logcat view, kept pure so they can be tested directly.
 *
 * Matching happens on every incoming line on a device with a busy log, so the predicate is
 * cheap: an ordinal comparison, a set lookup, and at most one substring or regex test.
 */
data class LogcatFilter(
    val minLevel: LogLevel = LogLevel.VERBOSE,
    val query: String = "",
    val useRegex: Boolean = false,
    /** Empty means "any process". Populated from the selected package's PIDs. */
    val pids: Set<Int> = emptySet(),
    val tag: String = "",
) {

    private val regex: Regex? = when {
        useRegex && query.isNotBlank() -> runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        else -> null
    }

    /** True when [query] is meant as a regex but does not compile, so the UI can say so. */
    val hasInvalidRegex: Boolean = useRegex && query.isNotBlank() && regex == null

    fun matches(entry: LogcatEntry): Boolean {
        if (!entry.level.isAtLeast(minLevel)) return false
        if (pids.isNotEmpty() && entry.pid !in pids) return false
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
}

/**
 * Ready-made filters for the situations developers actually open logcat for.
 *
 * This is where the panel earns its place next to Android Studio's Logcat window: the
 * presets are scoped to the app under development and to the failure being chased, rather
 * than being a general-purpose log viewer.
 */
enum class LogcatPreset(val label: String, val description: String) {
    CURRENT_APP("Current app", "Everything logged by the app in the open project"),
    ERRORS("Errors only", "Error and fatal levels, any process"),
    CRASHES("Crashes", "Fatal exceptions and native crashes"),
    ANR("ANRs", "Application Not Responding reports"),
    NETWORK("Network", "HTTP clients, connectivity and socket activity"),
    ALL("Everything", "No filtering");

    fun toFilter(appPids: Set<Int>): LogcatFilter = when (this) {
        CURRENT_APP -> LogcatFilter(pids = appPids)
        ERRORS -> LogcatFilter(minLevel = LogLevel.ERROR)
        CRASHES -> LogcatFilter(
            minLevel = LogLevel.ERROR,
            query = "FATAL EXCEPTION|AndroidRuntime|signal \\d+|backtrace:|beginning of crash",
            useRegex = true,
        )
        ANR -> LogcatFilter(
            query = "ANR in|Reason: Input dispatching timed out|am_anr",
            useRegex = true,
        )
        NETWORK -> LogcatFilter(
            query = "OkHttp|Retrofit|HttpURLConnection|ConnectivityService|Socket|TrafficStats",
            useRegex = true,
        )
        ALL -> LogcatFilter()
    }
}

/**
 * Marks the lines a developer is usually scanning for, so they can be highlighted.
 *
 * A crash is several lines — the header, then the frames — so classification is per line and
 * the UI colours each one it recognises.
 */
object LogcatHighlighter {

    private val CRASH_MARKERS = listOf(
        "FATAL EXCEPTION",
        "AndroidRuntime",
        "beginning of crash",
        "backtrace:",
        "signal 11",
        "signal 6",
    )

    private val ANR_MARKERS = listOf(
        "ANR in",
        "Input dispatching timed out",
        "am_anr",
    )

    fun classify(entry: LogcatEntry): Highlight = when {
        ANR_MARKERS.any { entry.message.contains(it, ignoreCase = true) } -> Highlight.ANR
        CRASH_MARKERS.any { entry.message.contains(it, ignoreCase = true) } -> Highlight.CRASH
        entry.level == LogLevel.ASSERT -> Highlight.CRASH
        entry.level == LogLevel.ERROR -> Highlight.ERROR
        entry.level == LogLevel.WARN -> Highlight.WARNING
        else -> Highlight.NONE
    }

    enum class Highlight { NONE, WARNING, ERROR, CRASH, ANR }
}
