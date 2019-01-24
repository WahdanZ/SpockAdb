package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import javassist.NotFoundException
import spock.adb.isAppInstall
import spock.adb.premission.PermissionListItem
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit


class GrantPermissionCommand:Command2<String,PermissionListItem,Unit> {
    override fun execute(p:String, p2: PermissionListItem, project: Project, device: IDevice) {
        if (device.isAppInstall(p))
            device.executeShellCommand("pm grant $p ${p2.permission}", ShellOutputReceiver(), 15L, TimeUnit.SECONDS)
        else
            throw NotFoundException("Application $p not installed" )
    }
}