package spock.adb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice
import spock.adb.screen.SpockScreenToolWindow

/*
 * The controls that lived only as widgets on the old Device tab, as actions, so the Spock
 * Actions popup, Find Action and a keymap shortcut can reach them with every tool window closed.
 */

/** Diagnoses the current screen and copies the redacted report, ready for an AI assistant. */
class CopyScreenForAiAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { SpockScreenToolWindow.diagnose(it, thenCopy = true) }
    }
}

class ToggleDontKeepActivitiesAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Switch Don't keep activities on or off on the device"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.enableDisableDontKeepActivities(device.device)
}

class ToggleShowTapsAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Switch Show taps on or off on the device"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.enableDisableShowTaps(device.device)
}

class ToggleLayoutBoundsAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Switch Show layout bounds on or off on the device"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.enableDisableShowLayoutBounds(device.device)
}

class OpenDeepLinkAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Open a URL or deep link on the device"
    override fun perform(project: Project, device: ConnectedDevice) {
        val link = Messages.showInputDialog(project, "URL or deep link to open:", "Open Deep Link", null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        SpockAdbService.getInstance(project).controller.openDeepLink(link, device.device)
    }
}

class SendTextToDeviceAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Type text into the focused field on the device"
    override fun perform(project: Project, device: ConnectedDevice) {
        val text = Messages.showInputDialog(project, "Text to type on the device:", "Send Text", null)
            ?.takeIf { it.isNotEmpty() } ?: return
        SpockAdbService.getInstance(project).controller.inputOnDevice(text, device.device)
    }
}
