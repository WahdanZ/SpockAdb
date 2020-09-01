package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import spock.adb.ShellOutputReceiver
import spock.adb.models.FragmentData

class GetFragmentsCommand : Command<String, List<FragmentData>> {

    override fun execute(p: String, project: Project, device: IDevice): List<FragmentData> {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
        return getCurrentFragmentsFromLog(shellOutputReceiver.toString())
    }

    private fun getCurrentFragmentsFromLog(dumpsys: String): List<FragmentData> {
        val bulkTaskDetails = dumpsys.substringAfter("TASK", "")

        return if (bulkTaskDetails.contains("NavHostFragment")) {
            getFragmentsUsingOldMethod(bulkTaskDetails)
        } else {
            val bulkAddedFragmentsDetails = getBulkAddedFragmentsDetails(bulkTaskDetails)

            val addedFragments = getAddedFragments(bulkAddedFragmentsDetails)

            getFragments(addedFragments, bulkTaskDetails)
        }
    }

    private fun getFragmentsUsingOldMethod(bulkTaskDetails: String): List<FragmentData> {
        return bulkTaskDetails
            .split("Added Fragments:")[2]
            .lines()
            .map { it.trim() }
            .filter { (it.startsWith("#") && !it.contains("BackStackEntry")) }
            .map {
                FragmentData(it.split("{").first().split(" ").last())
            }
            .distinct()
    }

    private fun getBulkAddedFragmentsDetails(bulkTaskDetails: String): String {
        return bulkTaskDetails
            .substringAfterLast("Added Fragments:", "")
            .substringBefore("Back Stack", "")
    }

    private fun getAddedFragments(bulkAddedFragmentsDetails: String): List<FragmentData> =
        bulkAddedFragmentsDetails
            .lines()
            .filter { line -> line.isNotBlank() }
            .map { line ->
                FragmentData(
                    line
                        .substringAfter(": ", "")
                        .substringBefore("{", ""),
                    line
                        .substringAfter("{", "")
                        .substringBefore("}", "")
                )
            }

    private fun getFragments(addedFragments: List<FragmentData>, bulkTaskDetails: String): List<FragmentData> {

        var initDelimiter: String
        var endDelimiter: String
        addedFragments.forEachIndexed { index, fragment ->
            initDelimiter = "${fragment.fragment}{${fragment.fragmentIdentifier}}"
            endDelimiter = if (index == addedFragments.lastIndex) {
                "mParent=$initDelimiter"
            } else "${addedFragments[index + 1].fragment}{${addedFragments[index + 1].fragmentIdentifier}}"

            fragment.innerFragments = getAddedFragments(getBulkAddedFragmentsDetails(bulkTaskDetails.substringAfter(initDelimiter, "").substringBefore(endDelimiter, "")))
            if (fragment.innerFragments.isNotEmpty()) {
                getFragments(fragment.innerFragments, bulkTaskDetails)
            }
        }

        return addedFragments
    }
}
