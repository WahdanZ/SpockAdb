package spock

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import spock.adb.SpockAdbService
import spock.adb.SpockAdbViewer
import spock.adb.commandcenter.CommandCenterPanel
import spock.adb.logcat.LogcatPanel
import spock.adb.mcp.McpServerPanel
import spock.adb.uitree.UiInspectorPanel

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Runs on the EDT. The controller is owned by the project-scoped SpockAdbService and
        // resolves the ADB bridge lazily on a pooled thread, so nothing here blocks the UI
        // while ADB starts.
        val adbController = SpockAdbService.getInstance(project).controller

        val logcatPanel = LogcatPanel(project)
        val commandCenterPanel = CommandCenterPanel(project)
        val mcpPanel = McpServerPanel(project)
        val uiInspectorPanel = UiInspectorPanel(project)

        // Both panels are disposed with the tool window, which stops the logcat stream and
        // cancels any running command rather than leaking an ADB reader thread.
        Disposer.register(toolWindow.disposable, logcatPanel)
        Disposer.register(toolWindow.disposable, commandCenterPanel)
        Disposer.register(toolWindow.disposable, mcpPanel)
        Disposer.register(toolWindow.disposable, uiInspectorPanel)

        val viewer = SpockAdbViewer(project, toolWindow.disposable)
        // The device chosen in the Devices tab is the target for every tab, so there is one
        // answer to "which device is this acting on" across the whole tool window.
        viewer.onDeviceSelected { selected ->
            logcatPanel.setDevice(selected)
            commandCenterPanel.setDevice(selected)
            uiInspectorPanel.setDevice(selected)
        }
        viewer.initPlugin(adbController)

        val contentManager = toolWindow.contentManager
        contentManager.addContent(
            contentManager.factory.createContent(viewer, "Devices", false),
        )
        contentManager.addContent(
            contentManager.factory.createContent(logcatPanel, "Logcat", false),
        )
        contentManager.addContent(
            contentManager.factory.createContent(commandCenterPanel, "Commands", false),
        )
        contentManager.addContent(
            contentManager.factory.createContent(uiInspectorPanel, "UI Inspector", false),
        )
        contentManager.addContent(
            contentManager.factory.createContent(mcpPanel, "MCP Server", false),
        )
    }
}
