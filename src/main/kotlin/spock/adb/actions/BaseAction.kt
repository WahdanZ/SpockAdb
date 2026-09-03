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
            when {
                devices.isEmpty() ->
                    showNotifier(project = project, content = "No connected devices", type = NotificationType.ERROR)
                devices.size == 1 -> performAction(controller, devices.first())
                else -> showDeviceList(project, devices) { performAction(controller, it) }
            }
        }
    }

    private fun showDeviceList(project: Project, devices: List<IDevice>, block: (device: IDevice) -> Unit) {
        val names = devices.map { it.name }
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
