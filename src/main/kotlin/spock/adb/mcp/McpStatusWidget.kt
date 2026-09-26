package spock.adb.mcp

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import spock.adb.SpockAdbShell
import spock.adb.context.SpockSelection
import spock.adb.notification.CommonNotifier
import javax.swing.Timer

/**
 * Whether the MCP server is running, and whether an AI agent is driving a different device
 * from the one Spock ADB has selected.
 *
 * The server had a tab of its own among the device's tabs, though it is configured once and
 * then only checked on. Its state is a status-bar fact, like the device, and the mismatch — an
 * agent clearing data on one phone while the developer watches another — belongs where it is
 * seen with every tool window hidden, which is also where it now raises a balloon when it
 * starts.
 */
class McpStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "Spock ADB MCP Server"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = McpStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "SpockAdb.McpStatus"
    }
}

internal class McpStatusWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {

    private val service = McpServerService.getInstance()
    private val selection = SpockSelection.getInstance(project)
    private var statusBar: StatusBar? = null

    /** The mismatch last seen, so the balloon is raised when it starts rather than on every tick. */
    private var warnedAbout: String? = null

    /**
     * Start and stop do not announce themselves, so the state is read on a short timer. It reads
     * two fields; nothing here touches ADB or the network.
     */
    private val ticker = Timer(TICK_MS) { update() }

    private val callListener: (McpCall) -> Unit = {
        ApplicationManager.getApplication().invokeLater({ update() }) { project.isDisposed }
    }

    override fun ID(): String = McpStatusWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        service.addCallListener(callListener)
        selection.addListener(this) { _, _ -> update() }
        ticker.start()
    }

    override fun dispose() {
        ticker.stop()
        service.removeCallListener(callListener)
        statusBar = null
    }

    override fun getSelectedValue(): String = McpStatusText.of(service.isRunning, mismatch())

    override fun getTooltipText(): String = McpStatusText.tooltip(service.isRunning, service.port, mismatch())

    override fun getPopup(): JBPopup = popup()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getPopupStep(): ListPopup = popup()

    private fun update() {
        val mismatch = mismatch()
        if (mismatch != null && mismatch != warnedAbout) {
            CommonNotifier.showNotifier(
                project = project,
                content = "An AI agent is driving $mismatch, not the device selected in Spock ADB. " +
                    "Actions you run still apply to the selected device.",
                type = NotificationType.WARNING,
            )
        }
        warnedAbout = mismatch
        statusBar?.updateWidget(ID())
    }

    /** The serial an agent chose, when the server runs and it is not the selected device. */
    private fun mismatch(): String? {
        val target = service.targetedSerial ?: return null
        return target.takeIf { service.isRunning && it != selection.snapshot.device?.serialNumber }
    }

    private fun popup(): ListPopup {
        val manager = ActionManager.getInstance()
        val group = DefaultActionGroup().apply {
            listOf(
                "spock.adb.mcp.ToggleMcpServerAction",
                "spock.adb.mcp.RestartMcpServerAction",
            ).mapNotNull(manager::getAction).forEach(::add)
            add(Separator.create("Connect a Client"))
            listOf(
                "spock.adb.mcp.InstallMcpConfigurationAction",
                "spock.adb.mcp.CopyMcpStdioConfigurationAction",
                "spock.adb.mcp.CopyMcpConfigurationAction",
                "spock.adb.mcp.RotateMcpTokenAction",
            ).mapNotNull(manager::getAction).forEach(::add)
            add(Separator.create())
            add(DumbAwareAction.create("Show Agent Activity") { SpockAdbShell.openMcpActivity(project) })
            add(
                DumbAwareAction.create("MCP Settings…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SpockAdbConfigurable::class.java)
                },
            )
        }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Spock ADB: MCP Server",
            group,
            SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    private companion object {
        const val TICK_MS = 2000
    }
}

/** What the MCP indicator says, apart from Swing so it can be tested. */
internal object McpStatusText {

    fun of(running: Boolean, mismatch: String?): String = when {
        mismatch != null -> "⚠ MCP: agent on $mismatch"
        running -> "MCP: on"
        else -> "MCP: off"
    }

    fun tooltip(running: Boolean, port: Int?, mismatch: String?): String = when {
        mismatch != null ->
            "An AI agent chose $mismatch with android_select_device. Actions you run from Spock ADB " +
                "still apply to the device selected here. Click for the server's actions."
        running -> "The Spock ADB MCP server is running${port?.let { " on port $it" }.orEmpty()}. Click to manage it."
        else -> "The Spock ADB MCP server is stopped. Click to start it or connect a client."
    }
}
