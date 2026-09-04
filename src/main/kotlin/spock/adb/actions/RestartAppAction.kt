package spock.adb.actions

import com.intellij.openapi.project.Project
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice

class RestartAppAction : DeviceAwareAction() {
    override val baseDescription = "Force-stop the app and launch it again"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.restartApp(device.device)
}
