package spock.adb.parser

import spock.adb.models.BackStackData

/**
 * Parses `dumpsys activity activities` output into the per-package activity back stack.
 */
object BackStackParser {

    private const val EMPTY = ""
    private const val DELIMITER = "ActivityRecord"
    private const val HIST_PREFIX = "* Hist"
    private const val RESUMED_PREFIX = "mResumedActivity"
    private const val ACTIVITY_PREFIX_DELIMITER = "."

    /**
     * Digits and underscores are part of the character set here because package and class names
     * carry them — `com.android.launcher3`, `Main2Activity`, `my_app`. Matching only letters cut
     * the name short at the first digit, and the truncated name is what the popup then tried to
     * resolve to a class, so those activities could never be opened.
     */
    private const val NAME_CHARS = "[A-Za-z0-9_.]"

    val extractAppRegex = Regex("(A=|I=|u0\\s)($NAME_CHARS+)")
    val extractActivityRegex = Regex("(u0\\s$NAME_CHARS+/)($NAME_CHARS+)")

    /** Pre-Honeycomb layout: `Running activities` section listing `ActivityRecord` entries. */
    fun parseLegacy(bulkActivitiesData: String): List<BackStackData> =
        bulkActivitiesData
            .lines()
            .filter { it.contains(DELIMITER, ignoreCase = true) }
            .mapNotNull { line ->
                val appPackage = extractAppRegex.find(line)?.groups?.lastOrNull()?.value
                    ?: return@mapNotNull null
                val activityName = extractActivityRegex.find(line)?.groups?.lastOrNull()?.value
                    ?.let { qualify(it, appPackage) }
                    ?: return@mapNotNull null
                appPackage to activityName
            }
            .groupBy({ it.first }, { it.second })
            .toBackStack(resumedPackage = null)

    /** Modern layout: `* Hist #n` lines, one per activity in the stack. */
    fun parseHistory(bulkActivitiesData: String): List<BackStackData> {
        var appPackage: String
        return bulkActivitiesData
            .lines()
            .filter { it.trim().startsWith(HIST_PREFIX) }
            .groupBy(
                keySelector = { line ->
                    appPackage = extractAppRegex.find(line)?.groups?.lastOrNull()?.value ?: EMPTY
                    appPackage
                },
                valueTransform = { line ->
                    val pkg = extractAppRegex.find(line)?.groups?.lastOrNull()?.value ?: EMPTY
                    extractActivityRegex.find(line)?.groups?.lastOrNull()?.value
                        ?.let { qualify(it, pkg) }
                        ?: EMPTY
                },
            )
            .filter { it.key.isNotBlank() }
            .toBackStack(resumedPackage = resumedPackage(bulkActivitiesData))
    }

    /**
     * The package of `mResumedActivity`, which is the task the user is looking at.
     *
     * Absent from dumps taken before the plugin started asking for the line, and from devices
     * that report no resumed activity at all, so callers fall back to stack order.
     */
    private fun resumedPackage(bulkActivitiesData: String): String? =
        bulkActivitiesData
            .lines()
            .firstOrNull { it.trim().startsWith(RESUMED_PREFIX) }
            ?.let { extractAppRegex.find(it)?.groups?.lastOrNull()?.value }
            ?.takeIf { it.isNotBlank() }

    /**
     * Turns grouped `package -> activities` pairs into the model the UI reads.
     *
     * Blank activity names are dropped here rather than in the renderer: an unreadable
     * `dumpsys` line is a parsing outcome, and letting it through produced an empty row in the
     * popup. The task itself is kept, because "this app is on the stack, but none of its
     * activities could be read" is still worth showing.
     */
    private fun Map<String, List<String>>.toBackStack(resumedPackage: String?): List<BackStackData> =
        entries.mapIndexed { index, (appPackage, activities) ->
            BackStackData(
                appPackage = appPackage,
                activitiesList = activities.filter { it.isNotBlank() },
                isForeground = if (resumedPackage != null) appPackage == resumedPackage else index == 0,
            )
        }

    /** `dumpsys` abbreviates activities in the declaring package to `.MainActivity`. */
    private fun qualify(activityName: String, appPackage: String): String =
        when {
            activityName.startsWith(ACTIVITY_PREFIX_DELIMITER) -> "$appPackage$activityName"
            else -> activityName
        }
}
