package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetFragmentsCommand : Command<Any, List<String?>?> {
    override fun execute(p: Any, project: Project, device: IDevice): List<String?>? {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
       return  getCurrentFragmentsFromLog(shellOutputReceiver)


    }

    private fun getCurrentFragmentsFromLog(shellOutputReceiver: ShellOutputReceiver):List<String>? {
        return shellOutputReceiver.toString().split("Added Fragments:").lastOrNull()?.split("\n")
            ?.filter {
                it.contains("#")
            }?.map {
                it.split("{").first()
                    .split(" ")
                    .last()
            }?.filter { !it.contains(".") }?.distinct()
    }

}