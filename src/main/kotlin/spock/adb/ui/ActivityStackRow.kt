package spock.adb.ui

import spock.adb.models.BackStackData

/**
 * One line of the Activity Stack popup.
 *
 * The popup used to be handed pre-formatted strings such as `\t\t\t\t0-com.example.app.MainActivity`,
 * which put parser indexes and tab padding in front of the developer and rendered an unreadable
 * `dumpsys` line as the bare string `0-`. Rows carry the parts instead, and [ActivityStackRenderer]
 * decides how they look.
 */
internal sealed interface ActivityStackRow {

    /**
     * The app whose activities follow.
     *
     * [appLabel] is the name the device shows for the app, and is null whenever that could not
     * be proven — see `AppLabelParser`. The package is shown either way: as the heading when
     * there is no label, and beneath it when there is.
     */
    data class Task(
        val appPackage: String,
        val appLabel: String? = null,
        val isForeground: Boolean = false,
    ) : ActivityStackRow

    /** One activity of [appPackage]'s stack, [className] fully qualified. */
    data class Activity(val className: String, val appPackage: String) : ActivityStackRow

    /** A task on the stack with no activity this plugin could read. */
    data class NoActivity(val appPackage: String) : ActivityStackRow
}

/**
 * What the row reads as in the popup.
 *
 * An activity declared in its task's own package is shortened to the part that differs, which is
 * what makes a stack scannable; anything else keeps its full name, because a package that is not
 * the task's own is the interesting half of it. The full name is always in [tooltip].
 */
internal fun ActivityStackRow.displayText(): String = when (this) {
    is ActivityStackRow.Task -> appLabel ?: appPackage
    is ActivityStackRow.Activity -> className.removePrefix("$appPackage.").ifBlank { className }
    is ActivityStackRow.NoActivity -> NO_ACTIVITY_TEXT
}

/** The quieter second line under [displayText], where one adds something it does not already say. */
internal fun ActivityStackRow.secondaryText(): String? =
    (this as? ActivityStackRow.Task)?.takeIf { it.appLabel != null }?.appPackage

/** The full name behind [displayText], or null where the row already shows everything it has. */
internal fun ActivityStackRow.tooltip(): String? = when (this) {
    is ActivityStackRow.Task -> appPackage
    is ActivityStackRow.Activity -> className
    is ActivityStackRow.NoActivity -> null
}

/** The class to open when the row is chosen, or null for a row that names no class. */
internal fun ActivityStackRow.className(): String? = (this as? ActivityStackRow.Activity)?.className

internal const val NO_ACTIVITY_TEXT = "No resumed activity"

/** Badge shown against the task the user is looking at. */
internal const val CURRENT_BADGE = "CURRENT"

/**
 * Flattens the back stack into popup rows: a task line per package, then its activities.
 *
 * [labels] holds the app names that could be read from the device, by package; a package missing
 * from it is shown as itself. A task with no readable activity still gets a line of its own
 * rather than nothing, so the stack stays complete and the gap is named instead of showing up as
 * an empty row.
 */
internal fun List<BackStackData>.toActivityStackRows(
    labels: Map<String, String> = emptyMap(),
): List<ActivityStackRow> = buildList {
    this@toActivityStackRows.forEach { task ->
        add(
            ActivityStackRow.Task(
                appPackage = task.appPackage,
                appLabel = labels[task.appPackage]?.takeIf { it.isNotBlank() && it != task.appPackage },
                isForeground = task.isForeground,
            ),
        )
        val activities = task.activitiesList.filter { it.isNotBlank() }
        if (activities.isEmpty()) {
            add(ActivityStackRow.NoActivity(task.appPackage))
        } else {
            activities.forEach { add(ActivityStackRow.Activity(it, task.appPackage)) }
        }
    }
}
