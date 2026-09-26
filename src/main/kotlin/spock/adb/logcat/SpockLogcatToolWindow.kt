package spock.adb.logcat

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import spock.adb.SpockAdbShell
import spock.adb.assistant.AssistantFeature
import spock.adb.assistant.AssistantPrefill
import spock.adb.context.SpockSelection
import spock.adb.timeline.DebugTimelinePanel

/**
 * Logcat in a tool window of its own, docked at the bottom by default.
 *
 * It was a tab of the Spock ADB window, which put the log and the button that made it take
 * turns on screen: restart the app, switch to Logcat, read, switch back. A log wants the IDE's
 * width and a few lines of height, and the actions want a narrow column beside the editor — so
 * the two are docked where each fits, and both are visible at once.
 *
 * Its second tab is the Debug Timeline, the other stream a developer watches while acting.
 * It shows the device and app chosen in [SpockSelection], like every other Spock surface.
 */
class SpockLogcatToolWindow : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LogcatPanel(project)
        Disposer.register(toolWindow.disposable, panel)

        // Logcat hands prepared context to the Assistant rather than reaching into it: the tab
        // is brought forward and the prompt placed, and the developer presses Send. Wired only
        // when the Assistant tab exists.
        if (AssistantFeature.TAB_VISIBLE) {
            panel.assistant = AssistantPrefill { prompt -> SpockAdbShell.prefillAssistant(project, prompt) }
        }

        SpockSelection.getInstance(project).addListener(toolWindow.disposable) { snapshot, changes ->
            if (SpockSelection.Change.DEVICE in changes || SpockSelection.Change.APP in changes) {
                panel.setTarget(snapshot.device, snapshot.app)
            }
        }

        val contentManager = toolWindow.contentManager
        val logContent = contentManager.factory.createContent(panel, LOGCAT, false)
        contentManager.addContent(logContent)

        // The Debug Timeline is a stream watched while acting too, so it is the window's second
        // tab. An event opens where it came from: a log line here, anything else where it lives.
        val timeline = DebugTimelinePanel(project) { source ->
            when (source) {
                LOGCAT -> contentManager.setSelectedContent(logContent)
                SpockAdbShell.MCP_TAB -> SpockAdbShell.openMcpActivity(project)
                else -> SpockAdbShell.openTab(project, source)
            }
        }
        Disposer.register(toolWindow.disposable, timeline)
        contentManager.addContent(contentManager.factory.createContent(timeline, TIMELINE, false))
    }

    companion object {
        const val ID = "Spock Logcat"
        private const val LOGCAT = "Logcat"
        private const val TIMELINE = "Timeline"

        /** Opens the window, building it on first use, then hands its panel to [then]. */
        fun open(project: Project, then: (LogcatPanel) -> Unit = {}) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            toolWindow.activate {
                toolWindow.contentManager.contents
                    .firstNotNullOfOrNull { it.component as? LogcatPanel }
                    ?.let(then)
            }
        }
    }
}
