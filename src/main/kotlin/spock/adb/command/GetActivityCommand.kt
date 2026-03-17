package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetActivityCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? {
        var receiver = ShellOutputReceiver()
        execShell(device, receiver, "dumpsys activity activities | grep mResumedActivity")
        var outputStr = receiver.toString()
        if (outputStr.isEmpty()) {
            receiver = ShellOutputReceiver()
            execShell(device, receiver, "dumpsys activity activities | grep topResumedActivity")
            outputStr = receiver.toString()
        }
        return outputStr.split(" ").find { it.contains("/") }
            ?.replace("/.", ".")
            ?.replace("}", "")
            ?.replace(Regex(".+/"), "")
    }

    private fun execShell(device: IDevice, receiver: ShellOutputReceiver, command: String) {
        device.executeShellCommand(command, receiver, 15L, TimeUnit.SECONDS)
    }
}
