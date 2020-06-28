package spock
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.android.sdk.AndroidSdkUtils
import spock.adb.AdbController
import spock.adb.AdbControllerImp
import spock.adb.SpockAdbViewer

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val adbController:AdbController = AdbControllerImp(project, AndroidSdkUtils.getDebugBridge(project))
        val contentManager = toolWindow.contentManager
            val content = contentManager.factory.createContent(SpockAdbViewer(adbController,project), null, false)
            contentManager.addContent(content)
    }
}
