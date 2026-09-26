package spock.adb.screen

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import spock.adb.context.SpockSelection
import spock.adb.diagnostics.DiagnosePanel
import spock.adb.logcat.SpockLogcatToolWindow
import spock.adb.uitree.UiInspectorPanel

/**
 * The screen in front of the developer: the Diagnose report and the UI tree, in a tool window
 * docked on the right by default.
 *
 * Both were tabs of the Spock ADB window, so the tree and the code it points into took turns
 * with Home and Storage, and Diagnose's "Inspect UI" swapped the report out for the tree. A
 * tree wants height and sits well beside the editor, and the two are about the same capture —
 * so they share a window of their own, as its two tabs.
 *
 * Both show the device and app chosen in [SpockSelection].
 */
class SpockScreenToolWindow : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val inspector = UiInspectorPanel(project)
        lateinit var treeContent: Content
        val diagnose = DiagnosePanel(
            project,
            inspectUi = {
                contentManager.setSelectedContent(treeContent)
                inspector.captureNow()
            },
            viewRelatedLogs = { SpockLogcatToolWindow.open(project) { it.showRelatedErrors() } },
        )
        Disposer.register(toolWindow.disposable, diagnose)
        Disposer.register(toolWindow.disposable, inspector)

        SpockSelection.getInstance(project).addListener(toolWindow.disposable) { snapshot, changes ->
            if (SpockSelection.Change.DEVICE in changes) {
                diagnose.setDevice(snapshot.device)
                inspector.setDevice(snapshot.device)
            }
            if (SpockSelection.Change.APP in changes) snapshot.app?.let(diagnose::setApp)
        }

        contentManager.addContent(contentManager.factory.createContent(diagnose, DIAGNOSE, false))
        treeContent = contentManager.factory.createContent(inspector, UI_TREE, false)
        contentManager.addContent(treeContent)
    }

    companion object {
        const val ID = "Spock Screen"
        private const val DIAGNOSE = "Diagnose"
        private const val UI_TREE = "UI Tree"

        /** Opens the window on the Diagnose report and diagnoses; [thenCopy] copies it for AI. */
        fun diagnose(project: Project, thenCopy: Boolean = false) =
            open(project, DIAGNOSE) { (it as? DiagnosePanel)?.diagnose(thenCopy) }

        /** Opens the window on the UI tree. */
        fun inspect(project: Project) = open(project, UI_TREE) {}

        private fun open(project: Project, tab: String, then: (Any) -> Unit) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            // Activating builds the content on first use, so the panels exist by the time this runs.
            toolWindow.activate {
                val content = toolWindow.contentManager.contents
                    .firstOrNull { it.displayName == tab } ?: return@activate
                toolWindow.contentManager.setSelectedContent(content)
                then(content.component)
            }
        }
    }
}
