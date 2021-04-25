package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.models.ActivityData
import java.util.concurrent.TimeUnit

class GetApplicationBackStackCommand : Command<String, List<ActivityData>> {

    companion object {
        val systemFragments = listOf("ReportFragment", "FragmentManager", "NavHostFragment", "BackStackEntry")
        val currentActiveActivity = Regex("([A-Z])\\w+=true")
        val activityRegex = Regex(" {2}ACTIVITY.*")
        val fragmentRegex = Regex("[a-zA-Z1-9]+\\{[a-z0-9}]")
        val addedFragmentRegex = Regex("#2: ADD [a-zA-Z1-9]+\\{[a-z0-9}]")
        val removedFragmentRegex = Regex("#1: REMOVE [a-zA-Z1-9]+\\{[a-z0-9}]")
    }

    override fun execute(p: String, project: Project, device: IDevice): List<ActivityData> {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys activity $p",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
        return getCurrentRunningActivities(shellOutputReceiver.toString())
    }

    private fun getCurrentRunningActivities(bulkActivitiesData: String): List<ActivityData> {
        val tasks = mutableListOf<ActivityData>()
        val lines = bulkActivitiesData.lines()
        lines.mapIndexed { index, s ->
            if (s.contains(activityRegex)) {
                val status = currentActiveActivity.find(lines[index + 2])?.value?.split("=")?.firstOrNull()
                    ?: ""
                val getActivityName = s.split(" ").find { it.contains("/") }!!.replace("/", "")
                tasks.add(
                    ActivityData(
                        activity = getActivityName,
                        fragment = listOf(),
                        status = status
                    )
                )
            }
            if (s.contains(fragmentRegex)) {
                val task = tasks.last()
                if (s.contains("Active Fragments:") && !s.contains("NavHostFragment")) {
                    val current = fragmentRegex.find(s)?.value?.split("{")?.firstOrNull() ?: ""
                    tasks[tasks.size - 1] = task.copy(fragment = task.fragment + listOf(current))
                }
                val removedFragment =
                    removedFragmentRegex.find(s)?.value?.split("{")?.firstOrNull()?.split("REMOVE ")?.lastOrNull()
                if (removedFragment != null) {
                    println(removedFragment)
                    tasks[tasks.size - 1] = task.copy(fragment = task.fragment + listOf(removedFragment))
                }
            }

        }
        return tasks.reversed()
    }
}
