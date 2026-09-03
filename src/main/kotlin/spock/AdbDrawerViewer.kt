package spock

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import spock.adb.SpockAdbService
import spock.adb.SpockAdbViewer

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Runs on the EDT. The controller is owned by the project-scoped SpockAdbService and
        // resolves the ADB bridge lazily on a pooled thread, so nothing here blocks the UI
        // while ADB starts.
        val adbController = SpockAdbService.getInstance(project).controller

        val viewer = SpockAdbViewer(project, toolWindow.disposable)
        viewer.initPlugin(adbController)

        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(viewer, null, false))
    }
}
