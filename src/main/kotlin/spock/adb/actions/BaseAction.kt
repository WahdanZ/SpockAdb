package spock.adb.actions

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.PopupChooserBuilder
import com.intellij.ui.components.JBList
import org.jetbrains.android.sdk.AndroidSdkUtils
import spock.adb.AdbController
import spock.adb.AdbControllerImp
import spock.adb.notification.CommonNotifier.Companion.showNotifier

abstract class BaseAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) = event.project?.run {
        val controller = AdbControllerImp(this, AndroidSdkUtils.getDebugBridge(this))
        controller.connectedDevices { list ->
            if (list.isNotEmpty())
                if(list.size > 1)
                    showDeviceList(project = this, devices = list) {
                        performAction(controller, it)
                    }
                else
                    performAction(controller, list[0])
            else
                showNotifier(project = this, content = "No Devices", type = NotificationType.ERROR)

        }
    } ?: cancelAction()

    private fun showDeviceList(project: Project, devices: List<IDevice>, block: (device: IDevice) -> Unit) {
        val list = JBList(devices.map { it.name })
        PopupChooserBuilder(list).apply {
            this.setTitle("Devices")
            this.setItemChoosenCallback {
                block(devices[list.selectedIndex])
            }
            this.createPopup().showCenteredInCurrentWindow(project)
        }

    }

    private fun cancelAction() {}
    abstract fun performAction(controller: AdbController, device: IDevice)
}