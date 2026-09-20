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
     * @param app what is known about the app's processes. When its PIDs are unknown, **App
     *   matches nothing** rather than everything: a scope that silently widens to the whole
     *   device is the one failure here that looks exactly like success, and it ends with
     *   another process's logs in a bug report. Related still works unresolved, because its
     *   other two rules — related tags, and lines naming the package — need no PID.
     */
    fun matches(entry: LogcatEntry, app: AppProcesses): Boolean = when (this) {
        ALL -> true
        APP -> app.contains(entry.pid)
        RELATED -> app.contains(entry.pid) ||
            LogcatSignals.isRelatedTag(entry.tag) ||
            app.isNamedIn(entry.message)
    }

    /**
     * Why this scope is not showing what its name promises, or null when it is.
     *
     * One source for the status bar and the AI context header: when they disagreed about
     * whether "App" meant App, the header was the one telling a model something false.
     */
    fun caveat(app: AppProcesses): String? = when {
        this == ALL -> null
        app.isResolved -> null
        this == RELATED -> "matching the app's tags and package references only — its process IDs are unknown"
        else -> when (app.state) {
            AppProcesses.State.RESOLVING -> "reading the app's process IDs…"
            AppProcesses.State.NOT_RUNNING -> "the app is not running, so it has no logs of its own"
            AppProcesses.State.FAILED -> "the app's process IDs could not be read from the device"
            else -> "no app is selected, so there is nothing to scope to"
        }
    }
}
