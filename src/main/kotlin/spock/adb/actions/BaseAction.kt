package spock.adb.actions

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import org.jetbrains.android.sdk.AndroidSdkUtils
import spock.adb.AdbController
import spock.adb.AdbControllerImp
import spock.adb.notification.CommonNotifier.Companion.showNotifier


abstract class BaseAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) = event.project?.run {
        val controller = AdbControllerImp(this, AndroidSdkUtils.getDebugBridge(this))
        // connectedDevices() is synchronous — it immediately invokes the callback with the
        // current bridge.devices list, so we can dispose right after to remove the
        // AndroidDebugBridge device-change listener that init() registered.
        controller.connectedDevices { list ->
            if (list.isNotEmpty())
                if (list.size > 1)
                    showDeviceList(project = this, devices = list) {
                        performAction(controller, it)
                    }
                else
                    performAction(controller, list[0])
            else
                showNotifier(project = this, content = "No Devices", type = NotificationType.ERROR)
        }
        controller.dispose() // remove the device-change listener; async ADB work is unaffected
    } ?: cancelAction()

    private fun showDeviceList(project: Project, devices: List<IDevice>, block: (device: IDevice) -> Unit) {
        val names = devices.map { it.name }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Devices")
            .setItemChosenCallback { selectedName ->
                val index = names.indexOf(selectedName)
                if (index >= 0) block(devices[index])
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun cancelAction() {}
    abstract fun performAction(controller: AdbController, device: IDevice)
}