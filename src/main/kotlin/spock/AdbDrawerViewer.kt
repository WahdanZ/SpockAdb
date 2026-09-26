package spock

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import spock.adb.SpockAdbService
import spock.adb.SpockAdbShell
import spock.adb.mcp.SpockAdbConfigurable

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Runs on the EDT. The controller is owned by the project-scoped SpockAdbService and
        // resolves the ADB bridge lazily on a pooled thread, so nothing here blocks the UI
        // while ADB starts.
        val adbController = SpockAdbService.getInstance(project).controller

        // One content, not seven. The tabs live inside it, under a line that says which device
        // and which app every one of them is about — the same answer as the status bar's.
        val shell = SpockAdbShell(project, toolWindow.disposable)
        shell.start(adbController)

        // What used to be a gear and a refresh in the header, where the IDE puts them.
        val manager = ActionManager.getInstance()
        toolWindow.setTitleActions(
            listOfNotNull(
                manager.getAction("spock.adb.actions.SpockActionsPopupAction"),
                DumbAwareAction.create("Refresh Devices and Apps", AllIcons.Actions.Refresh) { shell.refresh() },
            ),
        )
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup().apply {
                add(DumbAwareAction.create("Choose Actions Shown on Home…") { shell.customizeHome() })
                manager.getAction("spock.adb.actions.CustomizeSpockActionsAction")?.let(::add)
                add(
                    DumbAwareAction.create("Spock ADB Settings…") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, SpockAdbConfigurable::class.java)
                    },
                )
            },
        )

        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(shell, null, false))
    }
}
