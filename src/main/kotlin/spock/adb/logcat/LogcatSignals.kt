package spock.adb.logcat

/**
 * The line shapes a developer opens logcat to find: crashes, ANRs, network activity, and the
 * system components that act on one app.
 *
 * One table rather than three. The scope selector, the intent filter, the row highlighter and
 * the AI context builder all have to agree on what "a crash" is — when they each kept their own
 * list, the Crashes filter showed lines the highlighter did not colour, and the AI context
 * centred on a different event than the one on screen.
 *
 * Matching runs on every line of a busy device log, so each test is a set lookup or a substring
 * scan; nothing here compiles a regex per call.
 */
object LogcatSignals {

    /**
     * Tags that report *about* an app rather than from it.
     *
     * Deliberately short. "Related" is only useful if it stays quiet: the moment it admits the
     * general run of system chatter it is indistinguishable from All, and the developer goes
     * back to App and loses the one line — the ActivityManager kill, the ANR report — that
     * explains what happened to their process.
     */
    private val RELATED_TAGS = setOf(
        "androidruntime",
        "activitymanager",
        "activitytaskmanager",
        "windowmanager",
        "connectivityservice",
        "packagemanager",
        // Native crash reporting: the tombstone header and the frames beneath it.
        "debug",
        "libc",
        "system.err",
    )

    private val CRASH_TAGS = setOf("androidruntime", "debug", "libc")

    private val CRASH_MARKERS = listOf(
        "FATAL EXCEPTION",
        "beginning of crash",
        "backtrace:",
        "signal 11",
        "signal 6",
        "Fatal signal",
    )

    private val ANR_MARKERS = listOf(
        "ANR in",
        "Input dispatching timed out",
        "am_anr",
        "Reason: Input dispatching timed out",
    )

    private val NETWORK_TAGS = setOf(
        "okhttp",
        "okhttpclient",
        "retrofit",
        "httpurlconnection",
        "connectivityservice",
        "networksecurityconfig",
        "trafficstats",
        "cronetlogger",
        "apiclient",
    )

    private val NETWORK_MARKERS = listOf(
        "HTTP/1.1",
        "HTTP/2",
        "SSLHandshake",
        "SocketTimeoutException",
        "UnknownHostException",
        "ConnectException",
    )

    /** Frame lines of a Java stack trace, which arrive as their own logcat records. */
    private val TRACE_PREFIXES = listOf("at ", "Caused by:", "Suppressed:", "... ", "#0", "#1")

    fun isRelatedTag(tag: String): Boolean = tag.lowercase() in RELATED_TAGS

    fun isCrash(entry: LogcatEntry): Boolean = when {
        entry.level == LogLevel.ASSERT -> true
        // The frames of a trace carry the same tag as its header, so the whole crash survives
        // the Crashes filter rather than being reduced to its first line.
        entry.tag.lowercase() in CRASH_TAGS && entry.level.isAtLeast(LogLevel.ERROR) -> true
        else -> CRASH_MARKERS.any { entry.message.contains(it, ignoreCase = true) }
    }

    fun isAnr(entry: LogcatEntry): Boolean =
        ANR_MARKERS.any { entry.message.contains(it, ignoreCase = true) }

    fun isNetwork(entry: LogcatEntry): Boolean =
        entry.tag.lowercase() in NETWORK_TAGS ||
            NETWORK_MARKERS.any { entry.message.contains(it, ignoreCase = true) }

    /** True for a line that only makes sense underneath the exception header above it. */
    fun isStackFrame(entry: LogcatEntry): Boolean {
        val message = entry.message.trimStart()
        return TRACE_PREFIXES.any { message.startsWith(it) }
    }

    /** True for the first line of an exception report, the line the frames belong to. */
    fun isExceptionHeader(entry: LogcatEntry): Boolean =
        entry.message.contains("FATAL EXCEPTION", ignoreCase = true) ||
            EXCEPTION_HEADER.containsMatchIn(entry.message)

    /** `java.lang.IllegalStateException: Required value was null`, and its kin. */
    private val EXCEPTION_HEADER =
        Regex("""^\s*(Caused by:\s*)?([a-z][\w.]*\.)+[A-Z]\w*(Exception|Error|Throwable)""")
}
