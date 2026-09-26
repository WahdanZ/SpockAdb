package spock.adb

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.ui.JBUI
import spock.adb.assistant.AssistantFeature
import spock.adb.assistant.AssistantPanel
import spock.adb.backgroundwork.BackgroundWorkPanel
import spock.adb.commandcenter.CommandCenterPanel
import spock.adb.context.SpockSelection
import spock.adb.device.ConnectedDevice
import spock.adb.home.HomePanel
import spock.adb.mcp.McpServerPanel
import spock.adb.screen.SpockScreenToolWindow
import spock.adb.storage.AppStoragePanel
import spock.adb.ui.TabStrip
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The whole tool window: one device, one app, and the tabs that act on them.
 *
 * The plugin registered six IDE content tabs, each panel finding its own way to a device — the
 * Devices tab owned the dropdown and pushed its choice at the others, so Logcat and Commands
 * showed no sign of what they were attached to, and the app was nowhere at all because every
 * action resolved the open project's app module for itself.
 *
 * Here the header is above the tabs rather than inside one of them, so what a tab is about is
 * the same question wherever you are, and the answer is on screen. The status bar below them is
 * shared for the same reason: an action run on one tab is still the last thing that happened
 * when you move to another.
 */
class SpockAdbShell(
    private val project: Project,
    private val parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private var disposed = false

    private val header = ToolWindowHeader(project, parentDisposable)
    private val statusBar = ActionStatusBar()

    /**
     * Not a `JBTabbedPane`: seven tabs wrap onto four rows in a docked tool window, and its
     * scrolling layout cannot be used at all — `DarculaTabbedPaneUI` recurses between
     * `ensureSelectedTabIsVisible` and `tabForCoordinate` until the stack overflows.
     */
    private val tabs = TabStrip()

    private val home = HomePanel(project)
    private val storage = AppStoragePanel(project)
    private val commands = CommandCenterPanel(project)
    private val backgroundWork = BackgroundWorkPanel(project)

    /** Built when first asked for: the server is configured once and then only checked on. */
    private var mcp: McpServerPanel? = null

    /**
     * Built only when the tab is shown.
     *
     * Constructing it regardless would start its configuration read — a keychain lookup on a
     * pooled thread — for a panel nobody can reach.
     */
    private val assistant = if (AssistantFeature.TAB_VISIBLE) AssistantPanel(project) else null

    private val selection = SpockSelection.getInstance(project)

    /** The device the tabs were last told about, so a reconnect is not announced as a change. */
    private var selectedDevice: ConnectedDevice? = null

    private lateinit var controller: AdbController

    init {
        listOfNotNull(storage, commands, backgroundWork, assistant)
            .forEach { Disposer.register(parentDisposable, it) }
        Disposer.register(parentDisposable) { disposed = true }

        tabs.addTab(HOME_TAB, home)
        tabs.addTab(STORAGE_TAB, storage)
        tabs.addTab("Commands", commands)
        tabs.addTab(BACKGROUND_WORK_TAB, backgroundWork)
        assistant?.let { tabs.addTab(ASSISTANT_TAB, it) }
        // Read on arrival rather than on every device or app change: two dumpsys round trips,
        // one of them the whole alarm table, for a tab that may never be opened.
        // Storage lists again for the same reason: its tree is read once, and the app writes.
        tabs.onSelected = { title ->
            when (title) {
                BACKGROUND_WORK_TAB -> backgroundWork.onShown()
                STORAGE_TAB -> storage.onShown()
            }
        }

        // The header and the tabs are both about the whole window, so they sit together above
        // the content rather than the tabs being part of it.
        setToolbar(
            JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(tabs, BorderLayout.SOUTH)
            },
        )
        setContent(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty()
                add(tabs.content, BorderLayout.CENTER)
                add(statusBar, BorderLayout.SOUTH)
            },
        )
    }

    fun start(controller: AdbController) {
        this.controller = controller
        home.attach(controller)
        home.onDiagnose = { SpockScreenToolWindow.diagnose(project) }
        home.onCopyScreenForAi = { SpockScreenToolWindow.diagnose(project, thenCopy = true) }
        home.onBackgroundWork = { tabs.select(BACKGROUND_WORK_TAB) }

        controller.onResult { result -> statusBar.show(result) }
        header.settingsButton.addActionListener { home.showActionSettings() }
        header.refreshButton.addActionListener { statusBar.working("Reading the device list…") }
        // A change of app refused because of unapplied edits puts the selection back, so every
        // view names the app the Storage tab is still showing. Later rather than now: the refusal
        // arrives while the selection is still telling its listeners about the change.
        storage.onAppKept = { kept ->
            if (kept != null) {
                ApplicationManager.getApplication().invokeLater({ selection.selectApp(kept) }) { disposed }
            }
        }

        selection.addListener(parentDisposable) { snapshot, changes ->
            if (SpockSelection.Change.DEVICE in changes) selectDevice(snapshot.device)
            if (SpockSelection.Change.APP in changes) snapshot.app?.let(::selectApp)
        }
        listenForToolWindow()
    }

    // ---------------------------------------------------------------- device

    private fun selectDevice(device: ConnectedDevice?) {
        selectedDevice = device
        home.setDevice(device)
        storage.setDevice(device)
        commands.setDevice(device)
        backgroundWork.setDevice(device)
    }

    /** The app every tab and every action uses, as chosen in [SpockSelection]. */
    private fun selectApp(packageName: String) {
        storage.setApp(packageName)
        backgroundWork.setApp(packageName)
        home.setApp()
    }

    /** Adds the MCP server's tab the first time it is asked for, and brings it forward. */
    private fun showMcp() {
        if (mcp == null) {
            mcp = McpServerPanel(project).also {
                Disposer.register(parentDisposable, it)
                tabs.addTab(MCP_TAB, it)
            }
        }
        tabs.select(MCP_TAB)
    }

    private fun prefillAssistant(prompt: String) {
        val panel = assistant ?: return
        tabs.select(ASSISTANT_TAB)
        panel.prefill(prompt)
    }

    /** Brings the tab titled [title] forward, for the actions that open one. */
    fun selectTab(title: String) {
        tabs.select(title)
    }

    // ---------------------------------------------------------------- lifecycle

    private fun listenForToolWindow() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return

        project.messageBus
            .connect(parentDisposable)
            .subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    // The no-argument stateChanged() is deprecated; the ToolWindowManager
                    // overload has been available since 2020.1.
                    override fun stateChanged(toolWindowManager: ToolWindowManager) {
                        if (!toolWindow.isVisible) return
                        // Re-read the device list every time the panel is shown: without this a
                        // dropdown that came up empty — because ADB had not started yet, or a
                        // device was plugged in afterwards — could only be recovered by
                        // reopening the project.
                        selection.refresh()
                        home.onShown()
                    }
                },
            )
    }

    companion object {
        /**
         * The shell in [project]'s tool window, once the window has been built. The window has
         * one content — this — and the tabs are inside it, so a tab cannot be found as content.
         */
        fun find(project: Project): SpockAdbShell? =
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?.contentManager?.contents
                ?.firstNotNullOfOrNull { it.component as? SpockAdbShell }

        /**
         * Brings the Assistant tab forward with [prompt] placed, for Logcat's Ask AI. The
         * developer presses Send; see [spock.adb.assistant.AssistantPrefill].
         */
        fun prefillAssistant(project: Project, prompt: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate { find(project)?.prefillAssistant(prompt) }
        }

        /** Opens the MCP server's agent activity, from the status-bar indicator or the Tools menu. */
        fun openMcpActivity(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.activate { find(project)?.showMcp() }
        }

        const val MCP_TAB = "MCP Server"
        private const val TOOL_WINDOW_ID = "Spock ADB"
        private const val ASSISTANT_TAB = "Assistant"
        private const val BACKGROUND_WORK_TAB = "Background Work"
        private const val HOME_TAB = "Home"
        private const val STORAGE_TAB = "Storage"
    }
}
