package spock

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.android.sdk.AndroidSdkUtils
import spock.adb.AdbControllerImp
import spock.adb.SpockAdbViewer

class AdbDrawerViewer : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val adbController = AdbControllerImp(project, AndroidSdkUtils.getDebugBridge(project))
        // Use toolWindow.disposable, not project, as the parent — it is disposed when the
        // tool window is torn down (plugin unload / project close), which is the correct
        // lifetime for the device-change listener registered inside AdbControllerImp.
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable, adbController)
        val contentManager = toolWindow.contentManager

        with(SpockAdbViewer(project)) {
            initPlugin(adbController)
            contentManager.addContent(contentManager.factory.createContent(this, null, false))
        }
    }
}
