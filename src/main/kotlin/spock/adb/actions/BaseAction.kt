package spock.adb.actions

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import spock.adb.AdbController
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice
import spock.adb.notification.CommonNotifier.Companion.showNotifier

abstract class BaseAction : AnAction() {

    /**
     * Actions are enabled purely on the presence of a project, so the update can run on the
     * background thread. Required since 2022.3, where not declaring it is reported.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val controller = SpockAdbService.getInstance(project).controller

        // The device list is fetched off the EDT and delivered back on it. The controller is
        // owned by SpockAdbService, so unlike the previous implementation it is not disposed
        // out from under this callback — which used to cancel the work before it ran.
        controller.connectedDevices { devices ->
            val usable = devices.filter { it.info.isUsable }
            when {
                usable.isEmpty() -> showNotifier(
                    project = project,
                    content = if (devices.isEmpty()) {
                        "No connected devices"
                    } else {
                        devices.joinToString(prefix = "No device is ready: ") {
                            "${it.info.displayName} is ${it.info.state.label}"
                        }
                    },
                    type = NotificationType.ERROR,
                )
                usable.size == 1 -> performAction(controller, usable.first().device)
                else -> showDeviceList(project, usable) { performAction(controller, it.device) }
            }
        }
    }

    private fun showDeviceList(
        project: Project,
        devices: List<ConnectedDevice>,
        block: (device: ConnectedDevice) -> Unit,
    ) {
        val names = devices.map { it.info.label() }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Devices")
            .setItemChosenCallback { selectedName ->
                devices.getOrNull(names.indexOf(selectedName))?.let(block)
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    abstract fun performAction(controller: AdbController, device: IDevice)
}
