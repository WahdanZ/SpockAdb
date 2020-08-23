package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import spock.adb.ShellOutputReceiver

class GetFragmentsCommand : Command<String, List<String?>?> {
    override fun execute(p: String, project: Project, device: IDevice): List<String?>? {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
        return getCurrentFragmentsFromLog(shellOutputReceiver)
    }

    private fun getCurrentFragmentsFromLog(shellOutputReceiver: ShellOutputReceiver): List<String>? {
        val task = shellOutputReceiver.toString().split("TASK").lastOrNull()
        val addedFragments = if (task?.contains("NavHostFragment") == true)
            task.split("Added Fragments:")[2]
        else task?.split("Added Fragments:")?.lastOrNull()
        return addedFragments?.lines()?.map { it.trim() }
            ?.filter { (it.startsWith("#") && !it.contains("BackStackEntry")) }
            ?.map { it.split("{").first().split(" ").last() }?.distinct()
    }
}
