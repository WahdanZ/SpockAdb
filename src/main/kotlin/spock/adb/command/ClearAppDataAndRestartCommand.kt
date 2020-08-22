package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

import spock.adb.clearAppData
import spock.adb.getDefaultActivityForApplication
import spock.adb.isAppInstall
import spock.adb.startActivity

class ClearAppDataAndRestartCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        if (device.isAppInstall(p)) {
            device.clearAppData(p, 15L)

            val activity = device.getDefaultActivityForApplication(p)
            if (activity.isNotEmpty()) {
                device.startActivity(activity)
            } else {
                throw Exception("No Default Activity Found")
            }

        } else
            throw Exception("Application $p not installed")
    }
}
