package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.parser.ActivityParser
import java.util.concurrent.TimeUnit

class GetActivityCommand : Command<Any, String?> {

    override fun execute(p: Any, project: Project, device: IDevice): String? {
        // `mResumedActivity` was removed in Android 13; fall back to `topResumedActivity`.
        val resumed = execShell(device, "dumpsys activity activities | grep mResumedActivity")
        val output = resumed.ifEmpty {
            execShell(device, "dumpsys activity activities | grep topResumedActivity")
        }
        return ActivityParser.parseResumedActivity(output)
    }

    private fun execShell(device: IDevice, command: String): String {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(command, receiver, 15L, TimeUnit.SECONDS)
        return receiver.toString()
    }
}
