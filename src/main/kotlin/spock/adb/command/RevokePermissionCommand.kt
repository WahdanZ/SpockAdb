package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.isAppInstall
import spock.adb.premission.ListItem
import java.util.concurrent.TimeUnit

class RevokePermissionCommand : Command2<String, ListItem, Unit> {
    override fun execute(p: String, p2: ListItem, project: Project, device: IDevice) {
        if (device.isAppInstall(p))
            device.executeShellCommand("pm revoke $p ${p2.permission}", ShellOutputReceiver(), 15L, TimeUnit.SECONDS)
        else
            throw Exception("Application $p not installed")
    }
}
