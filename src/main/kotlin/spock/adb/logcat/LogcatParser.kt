package spock.adb.logcat

/**
 * Parses the `threadtime` logcat format, which is the one this plugin always requests:
 *
 * ```
 * 10-04 12:34:56.789  1234  1250 E MyTag: something went wrong
 * ```
 *
 * Kept pure so the format can be tested against recorded device output. Lines that do not
 * match — logcat's own banners such as `--------- beginning of main` — are still surfaced
 * rather than dropped, because silently swallowing log lines makes the panel untrustworthy.
 */
object LogcatParser {

    private const val TIMESTAMP = 1
    private const val PID = 2
    private const val TID = 3
    private const val LEVEL = 4
    private const val TAG = 5
    private const val MESSAGE = 6

    private val THREADTIME = Regex(
        """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?):\s?(.*)$""",
    )

    fun parse(line: String): LogcatEntry? {
        if (line.isBlank()) return null

        val match = THREADTIME.find(line)
            ?: return unparsed(line)

        val groups = match.groupValues
        val level = LogLevel.fromCode(groups[LEVEL].first()) ?: return unparsed(line)

        return LogcatEntry(
            timestamp = groups[TIMESTAMP],
            pid = groups[PID].toIntOrNull() ?: 0,
            tid = groups[TID].toIntOrNull() ?: 0,
            level = level,
            tag = groups[TAG].trim(),
            message = groups[MESSAGE],
            raw = line,
        )
    }

    /**
     * Anything that is not a record — separators, `beginning of main`, a wrapped stack frame
     * that arrived on its own line — is kept at INFO with no tag so it stays visible in the
     * stream and in exports.
     */
    private fun unparsed(line: String) = LogcatEntry(
        timestamp = "",
        pid = 0,
        tid = 0,
        level = LogLevel.INFO,
        tag = "",
        message = line,
        raw = line,
    )
}
