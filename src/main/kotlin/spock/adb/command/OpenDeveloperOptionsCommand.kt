package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class OpenDeveloperOptionsCommand : NoInputCommand<String> {

    override fun execute(project: Project, device: IDevice): String {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "am start -a com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )

        return "Opened Developer Options"
    }
}
