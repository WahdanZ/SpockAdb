package spock.adb.actions

import com.intellij.openapi.project.Project
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice

class GetCurrentActivityAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Open the source of the Activity currently on screen"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.currentActivity(device.device)
}
