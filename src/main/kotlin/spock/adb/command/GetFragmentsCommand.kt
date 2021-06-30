package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.models.FragmentData
import java.util.concurrent.TimeUnit

class GetFragmentsCommand : Command<String, List<FragmentData>> {

    override fun execute(p: String, project: Project, device: IDevice): List<FragmentData> {
        val shellOutputReceiver = ShellOutputReceiver()
//        device.executeShellCommand("dumpsys activity $p", shellOutputReceiver, 15L, TimeUnit.SECONDS)
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
        return getCurrentFragmentsFromLog(shellOutputReceiver.toString())
    }

    private fun getCurrentFragmentsFromLog(dumpsys: String): List<FragmentData> {
        val bulkTaskDetails = dumpsys.substringAfterLast("TASK", "")

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
            .substringBeforeLast("FragmentManager misc state:", "")
//            .substringBefore("Back Stack", "")
    }

    private fun getAddedFragments(bulkAddedFragmentsDetails: String): MutableList<FragmentData> =
        bulkAddedFragmentsDetails
            .lines()
            .filter { line -> line.isNotBlank() }
            .mapTo(mutableListOf()) { line ->
                FragmentData(
                    line
                        .substringAfter(": ", "")
                        .substringBefore("{", ""),
                    line
                        .substringAfter("{", "")
                        .substringBefore("}", "")
                )
            }

    val visibleHintStr = "mUserVisibleHint="
    private fun getFragments(addedFragments: MutableList<FragmentData>, bulkTaskDetails: String): List<FragmentData> {

        var initDelimiter: String
        var endDelimiter: String
        addedFragments.forEachIndexed { index, fragment ->
            initDelimiter = "${fragment.fragment}{${fragment.fragmentIdentifier}}"
            endDelimiter = "mParent=$initDelimiter"

            val fragmentStr = bulkTaskDetails.substringAfter(initDelimiter, "").substringBefore(endDelimiter, "")

            if(fragmentStr.contains("{parent=null}")) {
                fragment.isNullParent = true
            }

            val visibleIndex = fragmentStr.indexOf(visibleHintStr)
            if(visibleIndex >= 0) {
                fragment.isVisible = fragmentStr.substring(visibleIndex + visibleHintStr.length).startsWith("true")
            }

            fragment.innerFragments = getAddedFragments(getBulkAddedFragmentsDetails(fragmentStr))
            if (fragment.innerFragments.isNotEmpty()) {
                getFragments(fragment.innerFragments, bulkTaskDetails)
            }
        }

        addedFragments.removeAll { !it.isVisible || it.isNullParent }

        return addedFragments
    }
}
