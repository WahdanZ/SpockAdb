package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import javassist.NotFoundException
import spock.adb.*

class RestartAppCommand:Command<String,Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        if (device.isAppInstall(p)) {
            device.killApp(p, 15L)
            val activity = device.getDefaultActivityForApplication(p)
            if (activity.isNotEmpty()) {
                device.startActivity(activity)
            } else {
                throw NotFoundException("No Default Activity Found")
            }
        }
        else
            throw NotFoundException("Application $p not installed" )
    }
}
