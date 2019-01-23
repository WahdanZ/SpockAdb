package spock

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import spock.adb.AdbViewer

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

            val contentManager = toolWindow.contentManager
            val content = contentManager.factory.createContent(AdbViewer(), null, false)
            contentManager.addContent(content)
    }
}
