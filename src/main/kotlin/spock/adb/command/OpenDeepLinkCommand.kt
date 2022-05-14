package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class OpenDeepLinkCommand : Command<String, String> {

    override fun execute(p: String, project: Project, device: IDevice): String {
        device.executeShellCommand("am start -a android.intent.action.VIEW -d \"$p\"", ShellOutputReceiver(), 15L, TimeUnit.SECONDS)
        return "Open DeepLink $p"
    }
}
