package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.isAppInstall
import spock.adb.premission.ListItem
import java.util.concurrent.TimeUnit

class RevokePermissionCommand : Command2<String, ListItem, Unit> {
    override fun execute(p: String, p2: ListItem, project: Project, device: IDevice) {
        check(device.isAppInstall(p)) { "Application $p is not installed on this device" }
        device.executeShellCommand(
            "pm revoke ${ShellQuote.quote(p)} ${ShellQuote.quote(p2.name)}",
            ShellOutputReceiver(),
            15L,
            TimeUnit.SECONDS,
        )
    }
}
