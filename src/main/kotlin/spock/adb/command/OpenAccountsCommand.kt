package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class OpenAccountsCommand : NoInputCommand<String> {

    override fun execute(project: Project, device: IDevice): String {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "am start -a android.settings.SYNC_SETTINGS",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )

        return "Opened Accounts"
    }
}
