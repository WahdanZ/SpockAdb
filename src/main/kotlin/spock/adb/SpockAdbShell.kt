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
import spock.adb.assistant.AssistantPrefill
import spock.adb.backgroundwork.BackgroundWorkPanel
import spock.adb.commandcenter.CommandCenterPanel
import spock.adb.device.ConnectedDevice
import spock.adb.logcat.LogcatPanel
import spock.adb.mcp.McpCall
import spock.adb.mcp.McpServerPanel
import spock.adb.mcp.McpServerService
import spock.adb.storage.AppStoragePanel
import spock.adb.ui.TabStrip
import spock.adb.uitree.UiInspectorPanel
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
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

    private val header = ToolWindowHeader(project) { !disposed && !project.isDisposed }
    private val statusBar = ActionStatusBar()

    /**
     * Not a `JBTabbedPane`: seven tabs wrap onto four rows in a docked tool window, and its
     * scrolling layout cannot be used at all — `DarculaTabbedPaneUI` recurses between
     * `ensureSelectedTabIsVisible` and `tabForCoordinate` until the stack overflows.
     */
    private val tabs = TabStrip()

    private val devices = SpockAdbViewer(project)
    private val storage = AppStoragePanel(project)
    private val logcat = LogcatPanel(project)
    private val commands = CommandCenterPanel(project)
    private val uiInspector = UiInspectorPanel(project)
    private val backgroundWork = BackgroundWorkPanel(project)
    private val mcp = McpServerPanel(project)

    /**
     * Built only when the tab is shown.
     *
     * Constructing it regardless would start its configuration read — a keychain lookup on a
     * pooled thread — for a panel nobody can reach.
     */
    private val assistant = if (AssistantFeature.TAB_VISIBLE) AssistantPanel(project) else null

    private var connected: List<ConnectedDevice> = emptyList()
    private var selectedDevice: ConnectedDevice? = null

    private lateinit var controller: AdbController

    /**
     * Refreshed on every recorded MCP call rather than on a timer.
     *
     * The agent's target only changes through `android_select_device`, which is itself a
     * recorded call, so the one event that can make this label wrong is the one that fires it.
     */
    private val mcpCallListener: (McpCall) -> Unit = {
        ApplicationManager.getApplication().invokeLater({ refreshAgentTarget() }) { project.isDisposed }
    }

    init {
        listOfNotNull(storage, logcat, commands, uiInspector, backgroundWork, mcp, assistant)
            .forEach { Disposer.register(parentDisposable, it) }
        Disposer.register(parentDisposable) { disposed = true }

        // Logcat hands prepared context to the Assistant rather than reaching into it: the tab
        // is brought forward and the prompt placed, and the developer presses Send. See
        // [spock.adb.assistant.AssistantPrefill]. Wired only when the tab exists.
        assistant?.let { panel ->
            logcat.assistant = AssistantPrefill { prompt ->
                tabs.select(ASSISTANT_TAB)
                panel.prefill(prompt)
            }
        }

        tabs.addTab("Device", devices)
        tabs.addTab(STORAGE_TAB, storage)
        tabs.addTab("Logcat", logcat)
        tabs.addTab("Commands", commands)
        tabs.addTab("UI Inspector", uiInspector)
        tabs.addTab(BACKGROUND_WORK_TAB, backgroundWork)
        tabs.addTab("MCP Server", mcp)
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
        devices.initPlugin(controller)

        controller.onResult { result -> statusBar.show(result) }
        header.settingsButton.addActionListener { devices.showActionSettings() }
        header.refreshButton.addActionListener {
            statusBar.working("Reading the device list…")
            controller.refresh()
        }
        header.deviceCombo.addItemListener { event ->
            // A combo box fires DESELECTED then SELECTED, and reports index -1 when the model
            // is emptied. Indexing straight into the list threw ArrayIndexOutOfBoundsException
            // whenever the last device disconnected.
            if (event.stateChange == ItemEvent.SELECTED) {
                selectDevice(connected.getOrNull(header.deviceCombo.selectedIndex))
            }
        }
        header.onAppChosen = { packageName -> selectApp(packageName) }
        // A change of app refused because of unapplied edits has to put the header back, or the
        // header would name an app the Storage tab is not showing.
        storage.onAppKept = { kept -> if (kept != null) selectApp(kept) }

        watchAgentTarget()
        listenForToolWindow()
        observeDevices()
    }

    // ---------------------------------------------------------------- device

    private fun observeDevices() {
        controller.observeDevices { list ->
            connected = list

            // Match on serial rather than instance identity: ddmlib hands out a new IDevice
            // after a reconnect, so identity comparison silently reset the selection. Falls
            // back to the serial persisted from the previous session, then to the first device
            // that is actually usable, so the plugin does not default to an offline one.
            val preferred = selectedDevice?.serialNumber ?: persistedSerial()
            val next = list.firstOrNull { it.serialNumber == preferred }
                ?: list.firstOrNull { it.info.isUsable }
                ?: list.firstOrNull()

            if (list.isEmpty()) {
                // An empty dropdown with no explanation is indistinguishable from a broken
                // plugin. Say so, and say what to do about it.
                header.deviceCombo.model = DefaultComboBoxModel(arrayOf(NO_DEVICES))
                header.deviceCombo.isEnabled = false
                header.setDevice(
                    null,
                    hint = "Connect a device or start an emulator, then press Refresh. " +
                        "If a device is attached, check idea.log for ADB errors.",
                )
            } else {
                header.deviceCombo.isEnabled = true
                // The name and the Android version only: the serial and the architecture are in
                // the tooltip, where they do not push the name out of a docked tool window.
                header.deviceCombo.model = DefaultComboBoxModel(list.map { it.info.shortLabel() }.toTypedArray())
                next?.let { header.deviceCombo.selectedIndex = list.indexOf(it) }
            }
            // Swapping the model fires no SELECTED event when the chosen index is 0 — a single
            // device, first load, a reconnect — so the item listener cannot be relied on here.
            selectDevice(next, listChanged = true)
        }
    }

    private fun selectDevice(device: ConnectedDevice?, listChanged: Boolean = false) {
        val sameDevice = device != null && device.serialNumber == selectedDevice?.serialNumber
        selectedDevice = device
        rememberSelectedDevice()
        if (device != null || listChanged) header.setDevice(device, sameDevice = sameDevice)

        devices.setDevice(device)
        storage.setDevice(device)
        logcat.setDevice(device)
        commands.setDevice(device)
        uiInspector.setDevice(device)
        backgroundWork.setDevice(device)
        refreshAgentTarget()
    }

    /**
     * The app every tab and every action uses.
     *
     * Told to the controller rather than to each tab: the controller is where an action
     * resolved the project's app module for itself, which is what made the header a label.
     */
    private fun selectApp(packageName: String) {
        controller.selectedApp = packageName
        storage.setApp(packageName)
        backgroundWork.setApp(packageName)
        devices.setApp()
    }

    private fun persistedSerial(): String? =
        AppSettingService.getInstance().state.selectedDevice?.takeIf { it.isNotBlank() }

    private fun rememberSelectedDevice() {
        val service = AppSettingService.getInstance()
        val current = service.state
        val serial = selectedDevice?.serialNumber
        if (current.selectedDevice != serial) {
            service.loadState(current.copy(selectedDevice = serial))
        }
    }

    // ---------------------------------------------------------------- agents

    private fun watchAgentTarget() {
        val service = McpServerService.getInstance()
        service.addCallListener(mcpCallListener)
        Disposer.register(parentDisposable) { service.removeCallListener(mcpCallListener) }
        refreshAgentTarget()
    }

    private fun refreshAgentTarget() {
        val service = McpServerService.getInstance()
        val target = service.targetedSerial
        val mismatched = service.isRunning && target != null && target != selectedDevice?.serialNumber
        header.setAgentTarget(target.takeIf { mismatched })
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
                        controller.refresh()
                        devices.onShown()
                    }
                },
            )
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Spock ADB"
        const val NO_DEVICES = "No devices connected"
        const val ASSISTANT_TAB = "Assistant"
        const val BACKGROUND_WORK_TAB = "Background Work"
        const val STORAGE_TAB = "Storage"
    }
}
