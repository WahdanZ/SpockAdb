package spock

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import spock.adb.SpockAdbService
import spock.adb.SpockAdbShell

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Runs on the EDT. The controller is owned by the project-scoped SpockAdbService and
        // resolves the ADB bridge lazily on a pooled thread, so nothing here blocks the UI
        // while ADB starts.
        val adbController = SpockAdbService.getInstance(project).controller

        // One content, not seven. The tabs live inside it, under a header that says which
        // device and which app every one of them is about — a question each tab used to answer
        // for itself, or not at all.
        val shell = SpockAdbShell(project, toolWindow.disposable)
        shell.start(adbController)

        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(shell, null, false))
    }
}
