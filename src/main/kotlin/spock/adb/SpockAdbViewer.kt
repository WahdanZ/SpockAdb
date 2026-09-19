package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.*
import spock.adb.compat.DebuggerSupport
import spock.adb.device.ConnectedDevice
import spock.adb.mcp.McpCall
import spock.adb.mcp.McpServerService
import spock.adb.premission.CheckBoxDialog
import spock.adb.storage.AppStoragePanel
import spock.adb.ui.CollapsibleSection
import spock.adb.ui.VerticallyScrollablePanel
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ItemEvent
import javax.swing.*

class SpockAdbViewer(
    private val project: Project,
    /** Scopes the message bus connection; without a parent the connection is never released. */
    private val parentDisposable: Disposable,
) : SimpleToolWindowPanel(true) {
    // Components are constructed here rather than bound from SpockAdbViewer.form. The form
    // required a reflective `$$$setupUI$$$` call, kept field names in sync by hand across two
    // files, and laid every action out as a full-width row — roughly fifteen of them, so in a
    // docked tool window most of the panel was below the fold.

    /**
     * The device and the app every action below is about, pinned above the scrolling column.
     *
     * It used to be the first row of that column, so by the time a developer had scrolled to
     * Network or App storage the answer to "which device, which app" was off screen.
     */
    private val header = DeviceHeader(project)

    private val devicesListComboBox get() = header.deviceCombo
    private val setting get() = header.settingsButton

    private val currentActivityButton = JButton("Current activity").apply {
        toolTipText = "Open the class of the Activity now on screen in the editor"
    }
    private val currentFragmentButton = JButton("Current fragment").apply {
        toolTipText = "Open the class of the Fragment now on screen in the editor"
    }
    private val currentAppBackStackButton = JButton("App back stack").apply {
        toolTipText = "Activities and Fragments of the app in this project"
    }
    private val activitiesBackStackButton = JButton("All activities").apply {
        toolTipText = "The Activity back stack across every running app"
    }

    private val restartAppButton = JButton("Restart app")
    private val restartAppWithDebuggerButton = JButton("Attach debugger").apply {
        toolTipText = "Restart the app and attach the debugger"
    }
    private val forceKillAppButton = JButton("Force stop")
    private val testProcessDeathButton = JButton("Simulate process death").apply {
        toolTipText = "Background the app, kill its process, then relaunch it — as the system would " +
            "under memory pressure"
    }

    // Destructive actions carry an ellipsis: they open a confirmation rather than acting.
    private val clearAppDataButton = JButton("Clear data…")

    // No ellipsis: this one asks nothing, because it destroys nothing the app cannot rebuild.
    private val clearAppCacheButton = JButton("Clear cache").apply {
        toolTipText = "Delete only the app's internal cache and code_cache; needs a debuggable build"
    }
    private val clearAppDataAndRestartButton = JButton("Clear data and restart…").apply {
        toolTipText = "Delete all app data, then relaunch the app"
    }
    private val uninstallAppButton = JButton("Uninstall app…")

    private val permissionButton = JButton("Manage permissions…").apply {
        toolTipText = "Grant or revoke individual runtime permissions"
    }
    private val grantAllPermissionsButton = JButton("Grant all")
    private val revokeAllPermissionsButton = JButton("Revoke all…")

    private val developerOptions = DeveloperOptionsSection(GAP)

    private val wifiRow = NetworkToggleRow(Network.WIFI, "Wi-Fi", project)
    private val mobileDataRow = NetworkToggleRow(Network.MOBILE, "Mobile data", project)

    private val inputOnDeviceTextField = JBTextField()
    private val inputOnDeviceButton = JButton("Send")
    private val openDeepLinkTextField = JBTextField()
    private val openDeepLinkButton = JButton("Open")

    private val httpProxyRow = HttpProxyRow(project, GAP)

    /** Disposed with the tool window, which drops the answers to storage reads and writes still in flight. */
    private val appStorage = AppStoragePanel(project).also { Disposer.register(parentDisposable, it) }

    /** Kept only so the hidden, unimplemented "connect over IP" control still resolves. */
    private val adbWifi = JButton()

    /** Every action that can be pinned, and the button that runs it. */
    private val pinnable: Map<QuickAction, JButton> = mapOf(
        QuickAction.CURRENT_ACTIVITY to currentActivityButton,
        QuickAction.CURRENT_FRAGMENT to currentFragmentButton,
        QuickAction.APP_BACK_STACK to currentAppBackStackButton,
        QuickAction.ALL_ACTIVITIES to activitiesBackStackButton,
        QuickAction.RESTART_APP to restartAppButton,
        QuickAction.ATTACH_DEBUGGER to restartAppWithDebuggerButton,
        QuickAction.FORCE_STOP to forceKillAppButton,
        QuickAction.PROCESS_DEATH to testProcessDeathButton,
        QuickAction.CLEAR_DATA to clearAppDataButton,
        QuickAction.CLEAR_CACHE to clearAppCacheButton,
        QuickAction.CLEAR_DATA_AND_RESTART to clearAppDataAndRestartButton,
        QuickAction.UNINSTALL to uninstallAppButton,
        QuickAction.MANAGE_PERMISSIONS to permissionButton,
        QuickAction.GRANT_ALL_PERMISSIONS to grantAllPermissionsButton,
        QuickAction.REVOKE_ALL_PERMISSIONS to revokeAllPermissionsButton,
    )

    /** Pinning moves a button here out of its section, so both are laid out together. */
    private val quickActions = QuickActionsBar { applyVisibility() }

    /**
     * The sections that are nothing but action buttons, as data.
     *
     * They used to be four hardcoded `grid(...)` calls. Their contents now depend on what is
     * pinned and on what a search matches, so they are filled rather than built — which also
     * means an action switched off in settings leaves no hole in the grid where it was.
     */
    private val groups = listOf(
        ActionGroup(
            "Navigate",
            "navigate",
            listOf(
                QuickAction.CURRENT_ACTIVITY,
                QuickAction.CURRENT_FRAGMENT,
                QuickAction.APP_BACK_STACK,
                QuickAction.ALL_ACTIVITIES,
            ),
        ),
        ActionGroup(
            "App lifecycle",
            "lifecycle",
            listOf(
                QuickAction.RESTART_APP,
                QuickAction.ATTACH_DEBUGGER,
                QuickAction.FORCE_STOP,
                QuickAction.PROCESS_DEATH,
            ),
        ),
        ActionGroup(
            "Destructive",
            "destructive",
            listOf(
                QuickAction.CLEAR_DATA,
                QuickAction.CLEAR_CACHE,
                QuickAction.CLEAR_DATA_AND_RESTART,
                QuickAction.UNINSTALL,
            ),
        ),
        ActionGroup(
            "Permissions",
            "permissions",
            listOf(
                QuickAction.MANAGE_PERMISSIONS,
                QuickAction.GRANT_ALL_PERMISSIONS,
                QuickAction.REVOKE_ALL_PERMISSIONS,
            ),
        ),
    )

    /** What the settings dialog has switched on; a search narrows this further, and never it. */
    private val enabledActions = mutableMapOf<SpockAction, Boolean>()

    private var searchQuery = ""

    /** Shown when a search matches nothing, so the tab does not just go blank. */
    private val noMatches = JBLabel().apply {
        // The text carries what was typed, and a label whose text starts with a tag is markup.
        putClientProperty("html.disable", true)
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(GAP)
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    /** A section of nothing but action buttons: its heading, and the grid they are filled into. */
    private inner class ActionGroup(val title: String, val key: String, val actions: List<QuickAction>) {
        val content: JPanel = JPanel(GridLayout(0, COLUMNS, JBUI.scale(GAP), JBUI.scale(GAP))).apply {
            border = JBUI.Borders.empty(GAP, 0)
        }
        lateinit var section: CollapsibleSection
    }

    private var devices: List<ConnectedDevice> = emptyList()

    // Sections, so a group whose actions are all switched off hides its heading too.
    private lateinit var quickSection: CollapsibleSection
    private lateinit var developerSection: CollapsibleSection
    private lateinit var networkSection: CollapsibleSection
    private lateinit var sendSection: CollapsibleSection
    private lateinit var storageSection: CollapsibleSection

    /** Hidden as a whole, so switched-off toggles do not leave an empty padded band. */
    private lateinit var networkToggles: JPanel
    private lateinit var inputRow: JPanel
    private lateinit var deepLinkRow: JPanel
    private var selectedDevice: ConnectedDevice? = null
        set(value) {
            field = value
            deviceListeners.forEach { it(value) }
            // The mismatch is between this and the agent's choice, so changing either side
            // has to re-evaluate it.
            refreshAgentTarget()
        }

    /** Notified whenever the selected device changes, so other tool window tabs follow it. */
    private val deviceListeners = mutableListOf<(ConnectedDevice?) -> Unit>()

    fun onDeviceSelected(listener: (ConnectedDevice?) -> Unit) {
        deviceListeners += listener
        listener(selectedDevice)
    }

    /** The ddmlib handle every command still operates on. */
    private val selectedIDevice: IDevice? get() = selectedDevice?.device

    private lateinit var adbController: AdbController

    /** The scrolling column of sections, kept so App storage can be given the height left over. */
    private lateinit var sectionColumn: JPanel
    private val scrollPane: JScrollPane

    init {
        scrollPane = JScrollPane(buildLayout()).apply {
            border = JBUI.Borders.empty()
            // Never scroll sideways: the content shrinks to the panel instead, which is
            // what stops the second button column being clipped in a docked tool window.
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        setToolbar(header)
        setContent(scrollPane)
        // The tab is taller in an undocked tool window than in a docked one, and taller again
        // with the sections above App storage collapsed; the editor follows rather than staying
        // at the one height that fitted when it was written.
        scrollPane.viewport.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = resizeStorage()
            },
        )
        AppSettingService.getInstance().run {
            updateUi(state)
        }
        onDeviceSelected { appStorage.setDevice(it) }
    }

    /**
     * Gives App storage whatever height the sections above it leave.
     *
     * The tab is a scrolling column that sizes each section by its preferred height, so a
     * component in it cannot simply stretch: the height has to be worked out and handed over.
     * [AppStoragePanel.setAvailableHeight] has a floor, below which the tab scrolls again —
     * the honest answer when every other section is expanded in a short tool window.
     */
    private fun resizeStorage() {
        if (!storageSection.isVisible || !storageSection.isExpanded) return
        val viewport = scrollPane.viewport.height
        if (viewport <= 0) return

        // Everything the column holds except the storage panel itself, which is the part
        // being resized — measuring the whole column would feed its own height back in.
        val others = sectionColumn.preferredSize.height - appStorage.preferredSize.height
        if (appStorage.setAvailableHeight(viewport - others)) {
            sectionColumn.revalidate()
        }
    }

    /**
     * A compact, sectioned layout.
     *
     * Actions are laid out two to a row rather than one full-width row each, and grouped
     * under collapsible headings, so the common ones fit without scrolling in a docked tool
     * window. Destructive actions are separated into their own section rather than sitting
     * between navigation and lifecycle buttons where they can be hit by accident.
     */
    private fun buildLayout(): JComponent {
        pinnable.forEach { (action, button) ->
            prepare(button)
            quickActions.install(action, button)
        }
        // First, because that is what pinning one is for.
        quickSection = section("Quick actions", "quick", quickActions)
        groups.forEach { group -> group.section = section(group.title, group.key, group.content) }
        developerSection = section("Developer options", "developer", developerOptions)
        networkSection = section("Network", "network", networkContent())
        sendSection = section("Send to device", "send", sendContent())
        // Last, because it is by far the tallest: the buttons above stay in view without scrolling past a table.
        storageSection = section("App storage", "storage", appStorage)

        val sections = allSections()
        // Collapsing anything above App storage gives it that height instead.
        sections.forEach { section -> section.onToggled = { resizeStorage() } }

        sectionColumn = VerticallyScrollablePanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(GAP)
            sections.forEach { add(it) }
            add(noMatches)
            // Absorbs the slack so the sections stay at the top instead of stretching.
            add(Box.createVerticalGlue())
        }
        return sectionColumn
    }

    /**
     * Refreshed on every recorded MCP call rather than on a timer.
     *
     * The agent's target only changes through `android_select_device`, which is itself a
     * recorded call, so the one event that can make this label wrong is the one that fires it.
     */
    private val mcpCallListener: (McpCall) -> Unit = {
        ApplicationManager.getApplication().invokeLater(
            { refreshAgentTarget() },
        ) { project.isDisposed }
    }

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

    private fun prepare(button: JButton) {
        // Without this a button refuses to shrink below its label width, so two of them
        // side by side force the whole panel wider than the tool window.
        button.minimumSize = java.awt.Dimension(0, button.preferredSize.height)
        if (button.toolTipText == null) button.toolTipText = button.text.removeSuffix("…")
    }

    private fun allSections(): List<CollapsibleSection> =
        listOf(quickSection) + groups.map { it.section } +
            listOf(developerSection, networkSection, sendSection, storageSection)

    /** Field plus its action button, so the text and what it does stay adjacent. */
    private fun sendContent(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        // The whole row is hidden, label included: switching the action off used to leave a
        // lone "Text" label behind the field it belonged to.
        inputRow = fieldRow("Text", inputOnDeviceTextField, inputOnDeviceButton)
        deepLinkRow = fieldRow("Deep link", openDeepLinkTextField, openDeepLinkButton)
        add(inputRow)
        add(deepLinkRow)
    }

    /**
     * The network toggles plus the HTTP proxy row, which lives here rather than under "Send
     * to device" because a developer looking for why traffic stopped will look under Network.
     */
    private fun networkContent(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        networkToggles = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(GAP, 0)
            add(wifiRow)
            add(mobileDataRow)
        }
        add(networkToggles)
        add(httpProxyRow)
    }

    private fun fieldRow(label: String, field: JBTextField, button: JButton): JPanel =
        JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, field.preferredSize.height + JBUI.scale(GAP))
            border = JBUI.Borders.emptyTop(2)
            add(JBLabel(label), BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
            add(button, BorderLayout.EAST)
        }

    private fun section(title: String, key: String, content: JPanel): CollapsibleSection =
        CollapsibleSection(title, content, key).apply {
            alignmentX = LEFT_ALIGNMENT
        }

        fun initPlugin(adbController: AdbController) {
        this.adbController = adbController

        // Registered only once the controller exists: the listener calls into it, and
        // `adbController` is a lateinit property, so subscribing from the constructor risked
        // an UninitializedPropertyAccessException on an early tool window state change.
        setToolWindowListener()
        watchAgentTarget()

        updateDevicesList()

        setting.isEnabled = true
        setting.isVisible = true
        setting.addActionListener {
            AppSettingService.getInstance().run {
                state.let {
                    val dialog = CheckBoxDialog(it.list) { selectedItem ->
                        this.loadState(it.copy(list = it.list.map { item ->
                            if (item.name == selectedItem.name)
                                item.copy(isSelected = selectedItem.isSelected)
                            else item
                        }))
                        updateUi(it)
                    }
                    dialog.setLocationRelativeTo(null)
                    dialog.pack()
                    dialog.isVisible = true
                }

            }

        }
        adbWifi.isVisible = false
        devicesListComboBox.addItemListener { event ->
            // A combo box fires DESELECTED then SELECTED, and reports index -1 when the
            // model is emptied. Indexing straight into `devices` threw
            // ArrayIndexOutOfBoundsException whenever the last device disconnected.
            if (event.stateChange == ItemEvent.SELECTED) {
                selectedDevice = devices.getOrNull(devicesListComboBox.selectedIndex)
                rememberSelectedDevice()
                header.setDeviceDetails(selectedDevice)
                refreshDeviceState()
            }
        }
        activitiesBackStackButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentBackStack(device)
            }
        }
        currentAppBackStackButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentApplicationBackStack(device)
            }
        }
        currentActivityButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentActivity(device)
            }
        }
        currentFragmentButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentFragment(device)
            }
        }
        restartAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.restartApp(device)
            }
        }
        restartAppWithDebuggerButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.restartAppWithDebugger(device)
            }
        }
        forceKillAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.forceKillApp(device)
            }
        }
        testProcessDeathButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.testProcessDeath(device)
            }
        }
        clearAppDataButton.addActionListener {
            selectedDevice?.let { (device, deviceInfo) ->
                if (DestructiveActionConfirmation.confirmClearData(project, deviceInfo, andRestart = false)) {
                    adbController.clearAppData(device)
                }
            }
        }
        clearAppCacheButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.clearAppCache(device)
            }
        }
        clearAppDataAndRestartButton.addActionListener {
            selectedDevice?.let { (device, deviceInfo) ->
                if (DestructiveActionConfirmation.confirmClearData(project, deviceInfo, andRestart = true)) {
                    adbController.clearAppDataAndRestart(device)
                }
            }
        }
        uninstallAppButton.addActionListener {
            selectedDevice?.let { (device, deviceInfo) ->
                if (DestructiveActionConfirmation.confirmUninstall(project, deviceInfo)) {
                    adbController.uninstallApp(device)
                }
            }
        }

        permissionButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.getApplicationPermissions(device) { list ->
                    val dialog = CheckBoxDialog(list) { selectedItem ->
                        if (selectedItem.isSelected)
                            adbController.grantPermission(device, selectedItem)
                        else
                            adbController.revokePermission(device, selectedItem)
                    }
                    dialog.pack()
                    dialog.isVisible = true

                }
            }
        }
        grantAllPermissionsButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.grantOrRevokeAllPermissions(
                    device,
                    GetApplicationPermission.PermissionOperation.GRANT,

                    )
            }
        }
        revokeAllPermissionsButton.addActionListener {
            selectedDevice?.let { (device, deviceInfo) ->
                if (DestructiveActionConfirmation.confirmRevokeAllPermissions(project, deviceInfo)) {
                    adbController.grantOrRevokeAllPermissions(
                        device,
                        GetApplicationPermission.PermissionOperation.REVOKE,
                    )
                }
            }
        }
        inputOnDeviceButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.inputOnDevice(inputOnDeviceTextField.text, device)
            }
        }
        inputOnDeviceTextField.addActionListener { inputOnDeviceButton.doClick() }
        openDeepLinkButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.openDeepLink(openDeepLinkTextField.text, device)
            }
        }
        openDeepLinkTextField.addActionListener { openDeepLinkButton.doClick() }

        header.onSearch = { query -> search(query) }
        developerOptions.attach(adbController) { selectedDevice }
        httpProxyRow.attach(adbController) { selectedDevice }
        wifiRow.attach(adbController) { selectedDevice }
        mobileDataRow.attach(adbController) { selectedDevice }
    }

    /**
     * Re-reads everything on the tab that is state the device holds rather than the plugin's.
     *
     * Developer options belong here too: they were read only when the tool window was shown, so
     * selecting a second device left the first device's animation scales and switches on screen,
     * ready to be changed on a device they were never read from.
     */
    private fun refreshDeviceState() {
        httpProxyRow.refresh()
        wifiRow.refresh()
        mobileDataRow.refresh()
        developerOptions.refresh()
    }

    /**
     * Records what the settings dialog has switched on, then lays the tab out from it.
     *
     * Settings persist action names as text. An action that is renamed or removed leaves a
     * stale entry behind, and `SpockAction.valueOf` then threw IllegalArgumentException from
     * the constructor — which prevented the tool window from opening at all. Unknown entries
     * are ignored instead.
     */
    private fun updateUi(setting: AppSetting) {
        enabledActions.clear()
        setting.list.forEach { item ->
            val action = SpockAction.entries.firstOrNull { it.name == item.name.replace(" ", "_") }
            if (action != null) enabledActions[action] = item.isSelected
        }
        // Attaching a debugger needs the Android Studio execution tooling, which is absent in
        // some IDEs that bundle the Android plugin. Hide the action there rather than offering
        // a button that can only report an error.
        if (!DebuggerSupport.isAvailable) enabledActions[SpockAction.RESTART_DEBUG] = false

        // Only here, not in applyVisibility: this reads the device when the row is switched
        // back on, and a search must not cost a round trip per keystroke.
        val networkAppeared = isOn(SpockAction.TOGGLE_NETWORK) && !networkToggles.isVisible
        httpProxyRow.setActionVisible(isOn(SpockAction.HTTP_PROXY))

        applyVisibility()
        if (networkAppeared) refreshDeviceState()
    }

    /** Whether the settings dialog has this action switched on. Unknown actions are shown. */
    private fun isOn(action: SpockAction): Boolean = enabledActions[action] ?: true

    private fun shown(action: QuickAction): Boolean {
        val button = pinnable[action] ?: return false
        return isOn(action.gate) && matchesActionSearch(searchQuery, button.text, button.toolTipText)
    }

    private fun shown(action: SpockAction, terms: String): Boolean =
        isOn(action) && matchesActionSearch(searchQuery, terms)

    private fun search(query: String) {
        if (query == searchQuery) return
        searchQuery = query
        applyVisibility()
    }

    /**
     * Lays the tab out from the two things that decide what is on it: the settings, and the
     * search. Everything that hides a control goes through here, so the two cannot disagree —
     * a search that hid an action must not switch it off when the search is cleared.
     */
    private fun applyVisibility() {
        pinnable.forEach { (action, button) -> button.isVisible = shown(action) }

        // Pinned first: a group must not lay out a button the row above has taken.
        val pinnedButtons = quickActions.pinned.filter { shown(it) }.map { pinnable.getValue(it) }
        quickActions.fill(pinnedButtons)
        groups.forEach { fill(it) }

        networkToggles.isVisible = shown(SpockAction.TOGGLE_NETWORK, NETWORK_TERMS)
        httpProxyRow.isVisible = shown(SpockAction.HTTP_PROXY, PROXY_TERMS)
        inputRow.isVisible = shown(SpockAction.INPUT, INPUT_TERMS)
        deepLinkRow.isVisible = shown(SpockAction.DEEP_LINK, DEEP_LINK_TERMS)

        val searching = searchQuery.isNotBlank()
        // While searching, an empty Quick actions row is noise; its hint is not what was asked for.
        quickSection.setSectionVisible(pinnedButtons.isNotEmpty() || (!searching && quickActions.hasContent))
        developerSection.setSectionVisible(shown(SpockAction.DEVELOPER_OPTIONS, DEVELOPER_TERMS))
        networkSection.setSectionVisible(networkToggles.isVisible || httpProxyRow.isVisible)
        sendSection.setSectionVisible(inputRow.isVisible || deepLinkRow.isVisible)
        storageSection.setSectionVisible(shown(SpockAction.APP_STORAGE, STORAGE_TERMS))

        // A match inside a collapsed section is a match the developer cannot see.
        val sections = allSections()
        sections.forEach { it.setForcedExpanded(searching && it.isVisible) }
        // An empty tab is indistinguishable from a broken one; say which search emptied it.
        noMatches.isVisible = searching && sections.none { it.isVisible }
        if (noMatches.isVisible) noMatches.text = "No action matches \u201c$searchQuery\u201d."

        revalidate()
        repaint()
        resizeStorage()
    }

    /** Fills one group's grid with the actions it still holds: shown, and not pinned above. */
    private fun fill(group: ActionGroup) {
        val buttons = group.actions
            .filter { it !in quickActions.pinned && pinnable.getValue(it).isVisible }
            .map { pinnable.getValue(it) }
        group.content.removeAll()
        buttons.forEach { group.content.add(it) }
        // An odd count leaves the last button on its own row rather than stretched across two.
        if (buttons.size % COLUMNS != 0) group.content.add(JPanel())
        group.section.setSectionVisible(buttons.isNotEmpty())
    }

    /**
     * Rebuilds the device combo box. The callback is delivered on the EDT by
     * [AdbController.connectedDevices]; it used to arrive on a ddmlib thread and mutate the
     * Swing model directly.
     */
    private fun updateDevicesList() {
        adbController.observeDevices { connected ->
            this.devices = connected

            // Match on serial rather than instance identity: ddmlib hands out a new IDevice
            // after a reconnect, so identity comparison silently reset the selection.
            // Falls back to the serial persisted from the previous session, then to the
            // first device that is actually usable, so the plugin does not default to an
            // offline or unauthorised device.
            val preferredSerial = selectedDevice?.serialNumber ?: persistedDeviceSerial()
            selectedDevice = connected.firstOrNull { it.serialNumber == preferredSerial }
                ?: connected.firstOrNull { it.info.isUsable }
                ?: connected.firstOrNull()

            if (connected.isEmpty()) {
                // An empty dropdown with no explanation is indistinguishable from a broken
                // plugin. Say so, and say what to do about it.
                devicesListComboBox.model = DefaultComboBoxModel(arrayOf(NO_DEVICES_LABEL))
                devicesListComboBox.isEnabled = false
                header.setDeviceDetails(
                    null,
                    hint = "Connect a device or start an emulator, then press Refresh. " +
                        "If a device is attached, check idea.log for ADB errors.",
                )
            } else {
                devicesListComboBox.isEnabled = true
                // The name and the Android version only: the serial and the architecture are in
                // the tooltip, where they do not push the name out of a docked tool window.
                devicesListComboBox.model = DefaultComboBoxModel(
                    connected.map { it.info.shortLabel() }.toTypedArray(),
                )
                selectedDevice?.let { devicesListComboBox.selectedIndex = connected.indexOf(it) }
                header.setDeviceDetails(selectedDevice)
            }
            rememberSelectedDevice()
            // Swapping the model fires no SELECTED event when the chosen index is 0 — a single
            // device, first load, a reconnect — so the item listener cannot be relied on here.
            refreshDeviceState()
        }
    }

    private fun persistedDeviceSerial(): String? =
        AppSettingService.getInstance().state.selectedDevice?.takeIf { it.isNotBlank() }

    /**
     * Persists the chosen device so it is reselected next session.
     *
     * `AppSetting.selectedDevice` has existed since the settings were introduced but was
     * never read or written.
     */
    private fun rememberSelectedDevice() {
        val service = AppSettingService.getInstance()
        val current = service.state
        val serial = selectedDevice?.serialNumber
        if (current.selectedDevice != serial) {
            service.loadState(current.copy(selectedDevice = serial))
        }
    }

    private fun setToolWindowListener() {
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

                        // Re-read the device list every time the panel is shown. There is no
                        // refresh button in the form, so without this a dropdown that came up
                        // empty — because ADB had not started yet, or a device was plugged in
                        // afterwards — could only be recovered by reopening the project.
                        adbController.refresh()
                        // Gradle sync often finishes after the tool window is first built, and
                        // it is what resolves the application ID shown in the header.
                        header.refreshApp()
                        refreshDeviceState()
                        resizeStorage()
                    }
                },
            )
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Spock ADB"
        const val GAP = 4
        const val COLUMNS = 2
        const val NO_DEVICES_LABEL = "No devices connected"

        // What a search matches a whole section on, since these hold no action buttons to match.
        const val DEVELOPER_TERMS = "developer options don't keep activities show taps layout bounds " +
            "window transition animator animation duration scale"
        const val NETWORK_TERMS = "network wifi wi-fi mobile data connection"
        const val PROXY_TERMS = "network http proxy host port charles proxyman mitmproxy"
        const val INPUT_TERMS = "send to device text input type keyboard"
        const val DEEP_LINK_TERMS = "send to device deep link url intent open"
        const val STORAGE_TERMS = "app storage shared preferences sharedpreferences datastore file editor"
    }
}
