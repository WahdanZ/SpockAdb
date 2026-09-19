package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.*
import spock.adb.compat.DebuggerSupport
import spock.adb.device.ConnectedDevice
import spock.adb.premission.CheckBoxDialog
import spock.adb.ui.CollapsibleSection
import spock.adb.ui.ColumnsLayout
import spock.adb.ui.VerticallyScrollablePanel
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.Icon
import javax.swing.event.DocumentEvent

class SpockAdbViewer(
    private val project: Project,
) : SimpleToolWindowPanel(true) {
    // Components are constructed here rather than bound from SpockAdbViewer.form. The form
    // required a reflective `$$$setupUI$$$` call, kept field names in sync by hand across two
    // files, and laid every action out as a full-width row — roughly fifteen of them, so in a
    // docked tool window most of the panel was below the fold.

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

    /** Kept only so the hidden, unimplemented "connect over IP" control still resolves. */
    private val adbWifi = JButton()

    private val appInfoCard = AppInfoCard()

    /**
     * How many runtime permissions the app holds.
     *
     * The tab offered Grant all and Revoke all with no way to see what the app had, so the
     * answer to "did that take?" was to open the dialog and read a list.
     */
    private val permissionSummary = JBLabel(" ").apply {
        setFontColor(UIUtil.FontColor.BRIGHTER)
        border = JBUI.Borders.empty(2, 0)
    }

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
            "Danger zone",
            "destructive",
            listOf(
                QuickAction.CLEAR_DATA,
                QuickAction.CLEAR_CACHE,
                QuickAction.CLEAR_DATA_AND_RESTART,
                QuickAction.UNINSTALL,
            ),
            AllIcons.General.Warning,
        ),
        ActionGroup(
            "Permissions",
            "permissions",
            listOf(
                QuickAction.MANAGE_PERMISSIONS,
                QuickAction.GRANT_ALL_PERMISSIONS,
                QuickAction.REVOKE_ALL_PERMISSIONS,
            ),
            AllIcons.Actions.Lightning,
            permissionSummary,
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
    private inner class ActionGroup(
        val title: String,
        val key: String,
        val actions: List<QuickAction>,
        val icon: Icon? = null,
        /** Shown above the buttons, for a group that can say something about the app. */
        val summary: JBLabel? = null,
    ) {
        private val grid: JPanel = JPanel(GridLayout(0, COLUMNS, JBUI.scale(GAP), JBUI.scale(GAP))).apply {
            border = JBUI.Borders.empty(GAP, 0)
        }

        val content: JPanel = JPanel(BorderLayout()).apply {
            summary?.let { add(it, BorderLayout.NORTH) }
            add(grid, BorderLayout.CENTER)
        }

        lateinit var section: CollapsibleSection

        fun fillGrid(buttons: List<JButton>) {
            grid.removeAll()
            buttons.forEach { grid.add(it) }
            // An odd count leaves the last button on its own row rather than stretched across two.
            if (buttons.size % COLUMNS != 0) grid.add(JPanel())
        }
    }

    // Sections, so a group whose actions are all switched off hides its heading too.
    private lateinit var quickSection: CollapsibleSection
    private lateinit var appInfoSection: CollapsibleSection
    private lateinit var developerSection: CollapsibleSection
    private lateinit var networkSection: CollapsibleSection
    private lateinit var sendSection: CollapsibleSection

    /** Hidden as a whole, so switched-off toggles do not leave an empty padded band. */
    private lateinit var networkToggles: JPanel
    private lateinit var inputRow: JPanel
    private lateinit var deepLinkRow: JPanel
    private var selectedDevice: ConnectedDevice? = null

    /** The device chosen in the tool window's header, which is the one every tab acts on. */
    fun setDevice(connected: ConnectedDevice?) {
        selectedDevice = connected
        refreshDeviceState()
        refreshAppState()
    }

    /**
     * Re-reads what this tab shows about the device, when the tool window becomes visible.
     *
     * Developer options and the network rows are the device's state, not the plugin's, and a
     * device can be changed from anywhere while the tool window is hidden.
     */
    fun onShown() {
        refreshDeviceState()
        refreshAppState()
    }

    /** The app chosen in the tool window's header changed, so what this tab says about it has. */
    fun setApp() {
        refreshAppState()
    }

    /**
     * Re-reads what the tab says about the app: its version and UID, and how many permissions
     * it holds. Both are the app's state, not the plugin's.
     */
    private fun refreshAppState() {
        appInfoCard.refresh()
        refreshPermissionSummary()
    }

    private fun refreshPermissionSummary() {
        val device = selectedDevice
        if (device == null || !isOn(SpockAction.PERMISSIONS)) {
            permissionSummary.text = " "
            return
        }
        permissionSummary.text = "Reading permissions…"
        adbController.permissionSummary(device.device) { result ->
            permissionSummary.text = result.getOrNull()?.describe() ?: " "
        }
    }

    /** Opens the dialog that chooses which actions this tab shows. Driven from the header's gear. */
    fun showActionSettings() {
        AppSettingService.getInstance().run {
            val current = state
            val dialog = CheckBoxDialog(current.list) { selectedItem ->
                loadState(
                    current.copy(
                        list = current.list.map { item ->
                            if (item.name == selectedItem.name) {
                                item.copy(isSelected = selectedItem.isSelected)
                            } else {
                                item
                            }
                        },
                    ),
                )
                updateUi(current)
            }
            dialog.setLocationRelativeTo(null)
            dialog.pack()
            dialog.isVisible = true
        }
    }

    /** The ddmlib handle every command still operates on. */
    private val selectedIDevice: IDevice? get() = selectedDevice?.device

    private lateinit var adbController: AdbController

    private lateinit var sectionColumn: JPanel

    /**
     * Narrows the tab to the actions whose name answers what is typed.
     *
     * This tab's own control rather than the tool window's header: it searches these actions,
     * and on Logcat or Commands there would be nothing for it to search.
     */
    private val actionSearch = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search actions…"
        toolTipText = "Show only the matching actions, opening the sections that hold them"
        border = JBUI.Borders.empty(2, GAP)
    }

    init {
        setToolbar(actionSearch)
        setContent(
            JScrollPane(buildLayout()).apply {
                border = JBUI.Borders.empty()
                // Never scroll sideways: the content shrinks to the panel instead, which is
                // what stops the second button column being clipped in a docked tool window.
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            },
        )
        actionSearch.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = search(actionSearch.text)
            },
        )
        AppSettingService.getInstance().run {
            updateUi(state)
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
        markDestructive(clearAppDataButton, clearAppDataAndRestartButton, uninstallAppButton)
        // First, because that is what pinning one is for.
        quickSection = section("Quick actions", "quick", quickActions, AllIcons.Actions.Lightning)
        appInfoSection = section("App information", "appinfo", appInfoCard, AllIcons.Actions.Show)
        groups.forEach { group -> group.section = section(group.title, group.key, group.content, group.icon) }
        developerSection = section("Developer options", "developer", developerOptions, AllIcons.General.Settings)
        networkSection = section("Device connectivity", "network", networkContent(), AllIcons.General.Web)
        sendSection = section("Send to device", "send", sendContent(), AllIcons.Actions.Upload)

        val sections = allSections()
        // One column when docked narrow, more when there is room: seven cards of buttons run a
        // long way down a wide window with the right-hand half of it empty.
        sectionColumn = VerticallyScrollablePanel(ColumnsLayout()).apply {
            border = JBUI.Borders.empty(GAP)
            sections.forEach { add(it) }
            add(noMatches)
        }
        return sectionColumn
    }

    private fun prepare(button: JButton) {
        // Without this a button refuses to shrink below its label width, so two of them
        // side by side force the whole panel wider than the tool window.
        button.minimumSize = java.awt.Dimension(0, button.preferredSize.height)
        if (button.toolTipText == null) button.toolTipText = button.text.removeSuffix("…")
    }

    private fun allSections(): List<CollapsibleSection> =
        listOf(quickSection, appInfoSection) + groups.map { it.section } +
            listOf(networkSection, developerSection, sendSection)

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

    private fun section(title: String, key: String, content: JPanel, icon: Icon? = null): CollapsibleSection =
        CollapsibleSection(title, content, key, icon = icon).apply {
            alignmentX = LEFT_ALIGNMENT
        }

    fun initPlugin(adbController: AdbController) {
        this.adbController = adbController
        adbWifi.isVisible = false
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

        developerOptions.attach(adbController) { selectedDevice }
        appInfoCard.attach(adbController) { selectedDevice }
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

    /** Red on the two actions that destroy something, so they are not read as the four beside them. */
    private fun markDestructive(vararg buttons: JButton) {
        buttons.forEach { button ->
            button.foreground = DESTRUCTIVE
            button.border = JBUI.Borders.compound(
                com.intellij.ui.RoundedLineBorder(DESTRUCTIVE, JBUI.scale(BUTTON_ARC), 1),
                JBUI.Borders.empty(2, GAP),
            )
        }
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
        appInfoSection.setSectionVisible(shown(SpockAction.APP_INFO, APP_INFO_TERMS))
        developerSection.setSectionVisible(shown(SpockAction.DEVELOPER_OPTIONS, DEVELOPER_TERMS))
        networkSection.setSectionVisible(networkToggles.isVisible || httpProxyRow.isVisible)
        sendSection.setSectionVisible(inputRow.isVisible || deepLinkRow.isVisible)

        // A match inside a collapsed section is a match the developer cannot see.
        val sections = allSections()
        sections.forEach { it.setForcedExpanded(searching && it.isVisible) }
        // An empty tab is indistinguishable from a broken one; say which search emptied it.
        noMatches.isVisible = searching && sections.none { it.isVisible }
        if (noMatches.isVisible) noMatches.text = "No action matches \u201c$searchQuery\u201d."

        revalidate()
        repaint()
    }

    /** Fills one group's grid with the actions it still holds: shown, and not pinned above. */
    private fun fill(group: ActionGroup) {
        val buttons = group.actions
            .filter { it !in quickActions.pinned && pinnable.getValue(it).isVisible }
            .map { pinnable.getValue(it) }
        group.fillGrid(buttons)
        group.section.setSectionVisible(buttons.isNotEmpty())
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Spock ADB"
        const val GAP = 4
        const val COLUMNS = 2
        const val BUTTON_ARC = 6
        val DESTRUCTIVE = com.intellij.ui.JBColor(0xB3261E, 0xF2857C)
        const val NO_DEVICES_LABEL = "No devices connected"

        // What a search matches a whole section on, since these hold no action buttons to match.
        const val APP_INFO_TERMS = "app information package name version process uid identity build"
        const val DEVELOPER_TERMS = "developer options don't keep activities show taps layout bounds " +
            "window transition animator animation duration scale"
        const val NETWORK_TERMS = "network wifi wi-fi mobile data connection"
        const val PROXY_TERMS = "network http proxy host port charles proxyman mitmproxy"
        const val INPUT_TERMS = "send to device text input type keyboard"
        const val DEEP_LINK_TERMS = "send to device deep link url intent open"
        const val STORAGE_TERMS = "app storage shared preferences sharedpreferences datastore file editor"
    }
}
