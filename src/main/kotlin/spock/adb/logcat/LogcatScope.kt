package spock.adb.logcat

/**
 * *Which processes* the view is about — as opposed to [LogcatIntent], which is *what you are
 * looking for* inside them.
 *
 * The two used to be one list of presets, so choosing "Crashes" silently widened the view to
 * every process on the device and choosing "Current app" silently reset the level. Keeping them
 * apart is what makes "App + Crashes" and "Related + Errors" expressible at all, and it means a
 * filter can never move the scope out from under the developer.
 */
enum class LogcatScope(val label: String, val description: String) {

    APP("App", "Only the selected app's processes"),

    RELATED(
        "Related",
        "The app, plus the system components that act on it — ActivityManager, WindowManager, " +
            "ConnectivityService and the crash reporters",
    ),

    ALL("All", "Every line the device logs"),
    ;

    /**
     * @param appPids the resolved PIDs of the selected app. Empty means "not resolved yet",
     *   which matches everything rather than nothing: an empty panel while `pidof` is still in
     *   flight reads as a broken stream, and the filter re-runs as soon as the answer lands.
     */
    fun matches(entry: LogcatEntry, appPids: Set<Int>, appPackage: String): Boolean = when (this) {
        ALL -> true
        APP -> isAppProcess(entry, appPids)
        RELATED -> isAppProcess(entry, appPids) ||
            LogcatSignals.isRelatedTag(entry.tag) ||
            namesTheApp(entry, appPackage)
    }

    private fun isAppProcess(entry: LogcatEntry, appPids: Set<Int>): Boolean =
        appPids.isEmpty() || entry.pid in appPids

    /**
     * A system line that names the package — `ANR in com.example.app`, `Start proc … for
     * com.example.app` — is about this app whatever process wrote it, and is usually the only
     * record of what the system did to it.
     */
    private fun namesTheApp(entry: LogcatEntry, appPackage: String): Boolean =
        appPackage.isNotBlank() && entry.message.contains(appPackage, ignoreCase = true)
}
