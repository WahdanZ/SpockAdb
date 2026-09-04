package spock.adb.actions

import com.intellij.openapi.project.Project
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice

class GetCurrentApplicationBackStackAction : DeviceAwareAction() {
    override val baseDescription = "Show the Activity and Fragment stack for this app"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.currentApplicationBackStack(device.device)
}
