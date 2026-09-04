package spock.adb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import spock.adb.SpockAdbService
import spock.adb.device.ConnectedDevice

/**
 * Every major Spock ADB operation as an IntelliJ Action.
 *
 * Registered in plugin.xml under a single group, which is what puts them in Find Action and
 * in `Settings → Keymap → Spock ADB`. **No default shortcuts are declared**: verifying that
 * a binding is free across every keymap and platform is not something this plugin can do,
 * and silently taking a combination a developer already uses is worse than shipping none.
 * Users bind what they want through the standard Keymap UI.
 */

class ForceStopAppAction : DeviceAwareAction() {
    override val baseDescription = "Force-stop the app"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.forceKillApp(device.device)
}

class ClearAppDataAction : DeviceAwareAction() {
    override val baseDescription = "Delete all data for the app"
    override fun perform(project: Project, device: ConnectedDevice) {
        // Destructive: confirmed here rather than in the controller, so the Action and the
        // tool window button ask the same question in the same words.
        if (spock.adb.DestructiveActionConfirmation.confirmClearData(project, device.info, andRestart = false)) {
            SpockAdbService.getInstance(project).controller.clearAppData(device.device)
        }
    }
}

class ClearAppDataAndRestartAction : DeviceAwareAction() {
    override val baseDescription = "Delete all data for the app and relaunch it"
    override fun perform(project: Project, device: ConnectedDevice) {
        if (spock.adb.DestructiveActionConfirmation.confirmClearData(project, device.info, andRestart = true)) {
            SpockAdbService.getInstance(project).controller.clearAppDataAndRestart(device.device)
        }
    }
}

class UninstallAppAction : DeviceAwareAction() {
    override val baseDescription = "Uninstall the app from the device"
    override fun perform(project: Project, device: ConnectedDevice) {
        if (spock.adb.DestructiveActionConfirmation.confirmUninstall(project, device.info)) {
            SpockAdbService.getInstance(project).controller.uninstallApp(device.device)
        }
    }
}

class TestProcessDeathAction : DeviceAwareAction() {
    override val baseDescription = "Background the app, kill its process and relaunch it"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.testProcessDeath(device.device)
}

class ShowActivityStackAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Show the Activity back stack"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.currentBackStack(device.device)
}

class OpenDeveloperOptionsAction : DeviceAwareAction(requiresApplication = false) {
    override val baseDescription = "Open Developer Options on the device"
    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.openDeveloperOptions(device.device)
}

/** Opens a tool window tab. Needs no device, so it does not extend [DeviceAwareAction]. */
abstract class OpenTabAction(private val tabName: String) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return

        toolWindow.activate {
            toolWindow.contentManager.contents
                .firstOrNull { it.displayName == tabName }
                ?.let { toolWindow.contentManager.setSelectedContent(it) }
        }
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Spock ADB"
    }
}

class OpenLogcatAction : OpenTabAction("Logcat")
class OpenCommandCenterAction : OpenTabAction("Commands")
class OpenDevicesAction : OpenTabAction("Devices")
class OpenMcpPanelAction : OpenTabAction("MCP Server")
class OpenUiInspectorAction : OpenTabAction("UI Inspector")
