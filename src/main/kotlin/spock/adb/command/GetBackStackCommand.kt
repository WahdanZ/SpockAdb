package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.models.BackStackData
import java.util.concurrent.TimeUnit

class GetBackStackCommand : Command<Any, List<BackStackData>> {

    companion object {
        const val ACTIVITY_DELIMITER = "Running activities (most recent first):"
        const val ACTIVITY_PREFIX = "Run #"
        const val ACTIVITY_PREFIX_DELIMITER = "."
        val extractAppRegex = Regex("(A=|I=)([a-zA-Z.]+)")
        val extractActivityRegex = Regex("(u0\\s[a-zA-Z.]+/)([a-zA-Z.]+)")
    }

    override fun execute(p: Any, project: Project, device: IDevice): List<BackStackData> {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys activity activities | sed -En -e '/Running activities/,/Run #0/p'",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
        return getCurrentRunningActivities(shellOutputReceiver.toString())
    }

    private fun getCurrentRunningActivities(bulkActivitiesData: String): List<BackStackData> {
        lateinit var appPackage: String

        return bulkActivitiesData
            .split(ACTIVITY_DELIMITER)
            .filter { it.isNotBlank() }
            .mapNotNull { bulkAppData ->
                appPackage = extractAppRegex.find(bulkAppData)?.groups?.lastOrNull()?.value ?: return@mapNotNull null

                BackStackData(
                    appPackage = appPackage,
                    activitiesList = bulkAppData
                        .lines()
                        .filter { it.trim().startsWith(ACTIVITY_PREFIX) }
                        .mapNotNull { bulkActivityData: String ->
                            extractActivityRegex
                                .find(bulkActivityData)
                                ?.groups
                                ?.lastOrNull()
                                ?.value
                                ?.let { activityName ->
                                    when {
                                        activityName.startsWith(ACTIVITY_PREFIX_DELIMITER) -> "$appPackage$activityName"
                                        else -> activityName
                                    }
                                }
                        }
                )
            }
    }
}
