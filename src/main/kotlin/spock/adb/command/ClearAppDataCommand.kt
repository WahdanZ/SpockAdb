package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import javassist.NotFoundException
import spock.adb.clearAppData
import spock.adb.isAppInstall

class ClearAppDataCommand:Command<String,Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        if (device.isAppInstall(p)) {
            device.clearAppData(p, 15L)
        }
        else
            throw NotFoundException("Application $p not installed" )
    }
}