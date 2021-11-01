package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.getApiVersion
import spock.adb.models.BackStackData
import java.util.concurrent.TimeUnit

class GetBackStackCommand : Command<Any, List<BackStackData>> {

    companion object {
        const val EMPTY = ""
        const val API_VERSION_11 = 11
        const val DELIMITER = "ActivityRecord"
        const val HIST_PREFIX = "* Hist"
        const val ACTIVITY_PREFIX_DELIMITER = "."
        val extractAppRegex = Regex("(A=|I=|u0\\s)([a-zA-Z.]+)")
        val extractActivityRegex = Regex("(u0\\s[a-zA-Z.]+/)([a-zA-Z.]+)")
    }

    override fun execute(p: Any, project: Project, device: IDevice): List<BackStackData> {
        val shellOutputReceiver = ShellOutputReceiver()
        val apiVersion = device.getApiVersion()

        return when {
            apiVersion == null || apiVersion < API_VERSION_11 -> {
                device.executeShellCommand(
                    "dumpsys activity activities | sed -En -e '/Running activities/,/Run #0/p'",
                    shellOutputReceiver,
                    15L,
                    TimeUnit.SECONDS
                )
                getCurrentRunningActivities(shellOutputReceiver.toString())
            }
            else -> {
                device.executeShellCommand(
                    "dumpsys activity activities | grep Hist",
                    shellOutputReceiver,
                    15L,
                    TimeUnit.SECONDS
                )
                getCurrentRunningActivitiesAboveApi11(shellOutputReceiver.toString())
            }
        }
    }

    private fun getCurrentRunningActivities(bulkActivitiesData: String): List<BackStackData> {
        lateinit var appPackage: String
        lateinit var activityName: String

        return bulkActivitiesData
            .lines()
            .filter { line -> line.contains(DELIMITER, ignoreCase = true) }
            .mapNotNull { bulkAppData ->
                appPackage = extractAppRegex.find(bulkAppData)?.groups?.lastOrNull()?.value ?: return@mapNotNull null

                activityName = extractActivityRegex
                    .find(bulkAppData)
                    ?.groups
                    ?.lastOrNull()
                    ?.value
                    ?.let { activityName ->
                        when {
                            activityName.startsWith(ACTIVITY_PREFIX_DELIMITER) -> "$appPackage$activityName"
                            else -> activityName
                        }
                    }
                    ?: return@mapNotNull null

                appPackage to activityName
            }
            .groupBy({ group -> group.first }, { group -> group.second })
            .map { entry -> BackStackData(entry.key, entry.value) }
    }

    private fun getCurrentRunningActivitiesAboveApi11(bulkActivitiesData: String): List<BackStackData> {
        lateinit var appPackage: String

        return bulkActivitiesData
            .lines()
            .filter { line -> line.trim().startsWith(HIST_PREFIX) }
            .groupBy(
                keySelector = { line ->
                    appPackage = extractAppRegex.find(line)?.groups?.lastOrNull()?.value ?: EMPTY
                    appPackage
                },
                valueTransform = { bulkActivityData ->
                    extractActivityRegex.find(bulkActivityData)?.groups?.lastOrNull()?.value
                        ?.let { activityName ->
                            when {
                                activityName.startsWith(ACTIVITY_PREFIX_DELIMITER) -> "$appPackage$activityName"
                                else -> activityName
                            }
                        }
                        ?: EMPTY
                }
            )
            .filter { entry -> entry.key.isNotBlank() }
            .map { activityData -> BackStackData(activityData.key, activityData.value) }
    }
}
