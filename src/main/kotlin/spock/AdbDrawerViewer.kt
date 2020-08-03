package spock

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.android.sdk.AndroidSdkUtils
import spock.adb.AdbController
import spock.adb.AdbControllerImp
import spock.adb.SpockAdbViewer

class AdbDrawerViewer : ToolWindowFactory {

    private var spockAdbViewer: SpockAdbViewer? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val adbController: AdbController = AdbControllerImp(project, AndroidSdkUtils.getDebugBridge(project))
        val contentManager = toolWindow.contentManager

        if (spockAdbViewer == null) {
            spockAdbViewer = SpockAdbViewer(project)
            spockAdbViewer?.initPlugin(adbController)
        }

        val content = contentManager.factory.createContent(spockAdbViewer, null, false)
        contentManager.addContent(content)
    }
}
