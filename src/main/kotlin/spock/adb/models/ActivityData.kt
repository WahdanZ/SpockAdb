package spock.adb.models

data class ActivityData(val activity: String, val fragment: List<String>, val status: String = "")

/**
 * One task in the device's activity back stack.
 *
 * [activitiesList] holds fully qualified activity names, most recent first, and never holds a
 * blank entry: `dumpsys` lines the parser cannot read are dropped there rather than reaching the
 * UI as an empty row. A task whose activities were all unreadable therefore keeps its package
 * but carries an empty list, which the presentation layer renders as "no resumed activity".
 *
 * [isForeground] marks the task the user is looking at, taken from `mResumedActivity` when the
 * dump carries it and from stack order (top first) otherwise.
 */
class BackStackData(
    val appPackage: String,
    val activitiesList: List<String>,
    val isForeground: Boolean = false,
)
