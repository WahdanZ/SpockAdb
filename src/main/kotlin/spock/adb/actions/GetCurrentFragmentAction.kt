package spock.adb.actions

import com.intellij.openapi.project.Project
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice

class GetCurrentFragmentAction : DeviceAwareAction() {
    override val baseDescription = "Open the source of the visible Fragment"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.currentFragment(device.device)
}
