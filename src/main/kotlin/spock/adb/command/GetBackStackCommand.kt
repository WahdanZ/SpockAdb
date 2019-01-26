package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class GetBackStackCommand : Command<Any, List<String?>> {
    override fun execute(p: Any, project: Project, device: IDevice): List<String> {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys activity activities | sed -En -e '/Running activities/,/Run #0/p'",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
        return getCurrentRunningActivities(shellOutputReceiver)
    }

    private fun getCurrentRunningActivities(shellOutputReceiver: ShellOutputReceiver): List<String> {
        return shellOutputReceiver.toString().split('\n')
            .filter { it.contains("Run #") }
            .map { it.substringAfter("u0") }
            .map { it.substringAfter(" ").substringBefore(" ") }
            .map {
                if (it.contains("/."))
                    it.replace("/", "")
                else
                    it.substringAfter("/")
            }

    }
}