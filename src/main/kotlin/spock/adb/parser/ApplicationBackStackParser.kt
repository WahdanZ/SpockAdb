package spock.adb.parser

import spock.adb.models.ActivityData

/**
 * Parses `dumpsys activity <package>` output into the activity/fragment stack of one app.
 */
object ApplicationBackStackParser {

    private val currentActiveActivity = Regex("([A-Z])\\w+=true")
    private val activityRegex = Regex(" {2}ACTIVITY.*")
    private val fragmentRegex = Regex("[a-zA-Z1-9]+\\{[a-z0-9}]")
    private val removedFragmentRegex = Regex("#1: REMOVE [a-zA-Z1-9]+\\{[a-z0-9}]")

    fun parse(bulkActivitiesData: String): List<ActivityData> {
        val tasks = mutableListOf<ActivityData>()
        val lines = bulkActivitiesData.lines()

        lines.forEachIndexed { index, line ->
            if (line.contains(activityRegex)) {
                val status = currentActiveActivity
                    .find(lines.getOrNull(index + 2).orEmpty())
                    ?.value
                    ?.substringBefore('=')
                    .orEmpty()
                val activityName = line.split(" ").find { it.contains("/") }?.replace("/", "")
                if (activityName != null) {
                    tasks.add(ActivityData(activity = activityName, fragment = emptyList(), status = status))
                }
            }

            // Fragment lines are always nested under an ACTIVITY line. A malformed or
            // truncated dump can put one first, so never assume `tasks` is non-empty.
            if (line.contains(fragmentRegex) && tasks.isNotEmpty()) {
                val task = tasks.last()

                if (line.contains("Active Fragments:") && !line.contains("NavHostFragment")) {
                    val current = fragmentRegex.find(line)?.value?.substringBefore('{').orEmpty()
                    tasks[tasks.lastIndex] = task.copy(fragment = task.fragment + current)
                }

                val removedFragment = removedFragmentRegex.find(line)
                    ?.value
                    ?.substringBefore('{')
                    ?.substringAfterLast("REMOVE ")
                if (removedFragment != null) {
                    tasks[tasks.lastIndex] = tasks.last().copy(
                        fragment = tasks.last().fragment + removedFragment,
                    )
                }
            }
        }

        return tasks.reversed()
    }
}
