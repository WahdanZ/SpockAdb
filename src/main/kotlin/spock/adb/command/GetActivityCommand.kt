package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetActivityCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys activity activities | grep mResumedActivity",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
        return shellOutputReceiver.toString().split(" ").find { it.contains("/") }?.replace("/", "")
    }
}
