package spock.adb.parser

import spock.adb.models.BackStackData

/**
 * Parses `dumpsys activity activities` output into the per-package activity back stack.
 */
object BackStackParser {

    private const val EMPTY = ""
    private const val DELIMITER = "ActivityRecord"
    private const val HIST_PREFIX = "* Hist"
    private const val ACTIVITY_PREFIX_DELIMITER = "."

    val extractAppRegex = Regex("(A=|I=|u0\\s)([a-zA-Z.]+)")
    val extractActivityRegex = Regex("(u0\\s[a-zA-Z.]+/)([a-zA-Z.]+)")

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
            .map { (appPackage, activities) -> BackStackData(appPackage, activities) }

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
            .map { (appPackage, activities) -> BackStackData(appPackage, activities) }
    }

    /** `dumpsys` abbreviates activities in the declaring package to `.MainActivity`. */
    private fun qualify(activityName: String, appPackage: String): String =
        when {
            activityName.startsWith(ACTIVITY_PREFIX_DELIMITER) -> "$appPackage$activityName"
            else -> activityName
        }
}
