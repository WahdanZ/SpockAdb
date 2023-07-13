package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetActivityCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? {
        val shellOutputReceiver = ShellOutputReceiver()
        execShell(device, shellOutputReceiver, "dumpsys activity activities | grep mResumedActivity")
        var outputStr = shellOutputReceiver.toString()
        if(outputStr.isEmpty()){
            execShell(device, shellOutputReceiver,"dumpsys activity activities | grep topResumedActivity")
        }
        outputStr = shellOutputReceiver.toString()
        return outputStr.split(" ").find { it.contains("/") }
            ?.replace("/.", ".")
            ?.replace("}", "")
            ?.replace(Regex(".+/"), "")
    }

    private fun execShell(device: IDevice, receiver: ShellOutputReceiver, command: String){
        device.executeShellCommand(
            command,
            receiver,
            15L,
            TimeUnit.SECONDS
        )
    }
}
