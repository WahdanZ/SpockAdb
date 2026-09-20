package spock.adb.logcat

/**
 * *What you are looking for* — independent of [LogcatScope], so every combination is reachable:
 * App + Crashes, Related + Errors, All + ANRs.
 *
 * An intent narrows; it never widens. Choosing Crashes inside the App scope still shows only the
 * app's crashes, which is the whole point of separating the two.
 */
enum class LogcatIntent(val label: String, val description: String) {

    ALL("All logs", "No narrowing"),
    ERRORS("Errors", "Error and fatal levels"),
    CRASHES("Crashes", "Fatal exceptions, native crashes, and the frames beneath them"),
    ANRS("ANRs", "Application Not Responding reports"),
    NETWORK("Network", "HTTP clients, connectivity and socket activity"),
    ;

    fun matches(entry: LogcatEntry): Boolean = when (this) {
        ALL -> true
        ERRORS -> entry.level.isAtLeast(LogLevel.ERROR)
        CRASHES -> LogcatSignals.isCrash(entry)
        ANRS -> LogcatSignals.isAnr(entry)
        NETWORK -> LogcatSignals.isNetwork(entry)
    }
}
