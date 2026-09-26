package spock.adb.home

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.AdbController
import spock.adb.AppInfoCard
import spock.adb.AppSetting
import spock.adb.AppSettingService
import spock.adb.DestructiveActionConfirmation
import spock.adb.DeveloperOptionsSection
import spock.adb.HttpProxyRow
import spock.adb.LatestRequest
import spock.adb.NetworkToggleRow
import spock.adb.PushMessageRow
import spock.adb.SpockAction
import spock.adb.command.GetApplicationPermission
import spock.adb.command.Network
import spock.adb.compat.DebuggerSupport
import spock.adb.device.ConnectedDevice
import spock.adb.premission.CheckBoxDialog
import spock.adb.ui.CollapsibleSection
import spock.adb.ui.ColumnsLayout
import spock.adb.ui.VerticallyScrollablePanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

/**
 * The first thing in the Spock ADB window: the app, the screen it is on, and what can be done
 * to it.
 *
 * This replaces the Device tab, which was about thirty bordered buttons of equal weight in eight
 * sections — Restart beside Uninstall, and about three screens of scrolling when docked. Here
 * the answers are shown rather than fetched (which app, whether it is running, which activity is
 * in front), the four lifecycle actions are one toolbar, the destructive ones are behind a menu
 * rather than a single click, and everything about the device rather than the app is folded
 * into one section that stays closed until it is wanted.
 *
 * Every action it offers is the registered IDE action, so the toolbar, the Tools menu, Find
 * Action and a keymap shortcut are the same code and ask the same confirmations.
 */
class HomePanel(private val project: Project) : SimpleToolWindowPanel(true) {

    /** Opens the Diagnose report. Set by the tool window. */
    var onDiagnose: () -> Unit = {}

    /** Diagnoses the screen and copies the report for an AI assistant. Set by the tool window. */
    var onCopyScreenForAi: () -> Unit = {}

    /** Brings the background work forward. Set by the tool window. */
    var onBackgroundWork: () -> Unit = {}

    private lateinit var controller: AdbController
    private var device: ConnectedDevice? = null

    private val appInfoCard = AppInfoCard()
    private val screenCard = ScreenCard()

    private val permissionReads = LatestRequest()
    private val permissionSummary = JBLabel(" ").apply { setFontColor(UIUtil.FontColor.BRIGHTER) }
    private val permissionLinks = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP * 2), 0))

    private val developerOptions = DeveloperOptionsSection(GAP)
    private val wifiRow = NetworkToggleRow(Network.WIFI, "Wi-Fi", project)
    private val mobileDataRow = NetworkToggleRow(Network.MOBILE, "Mobile data", project)
    private val httpProxyRow = HttpProxyRow(project, GAP)
    private val pushMessageRow = PushMessageRow(project, GAP)
    private val inputField = JBTextField()
    private val inputButton = JButton("Send")
    private val deepLinkField = JBTextField()
    private val deepLinkButton = JButton("Open")
    private lateinit var inputRow: JPanel
    private lateinit var deepLinkRow: JPanel

    /** What the settings dialog has switched on. Unknown actions are shown. */
    private val enabled = mutableMapOf<SpockAction, Boolean>()

    private lateinit var permissionSection: CollapsibleSection
    private lateinit var developerSection: JComponent

    /** Reads the app and the screen again a moment after an action, which is when they change. */
    private val afterAction = Timer(AFTER_ACTION_MS) { refreshApp() }.apply { isRepeats = false }

    init {
        setContent(
            JScrollPane(buildLayout()).apply {
                border = JBUI.Borders.empty()
                // Never sideways: the content narrows to the panel instead.
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            },
        )
        applySettings(AppSettingService.getInstance().state)
    }

    fun attach(controller: AdbController) {
        this.controller = controller
        val target = { device }
        appInfoCard.attach(controller, target)
        screenCard.attach(controller, target)
        developerOptions.attach(controller, target)
        httpProxyRow.attach(controller, target)
        pushMessageRow.attach(controller, target)
        wifiRow.attach(controller, target)
        mobileDataRow.attach(controller, target)
        screenCard.copyForAiButton.addActionListener { onCopyScreenForAi() }
        screenCard.diagnoseButton.addActionListener { onDiagnose() }
        inputButton.addActionListener { device?.let { controller.inputOnDevice(inputField.text, it.device) } }
        inputField.addActionListener { inputButton.doClick() }
        deepLinkButton.addActionListener { device?.let { controller.openDeepLink(deepLinkField.text, it.device) } }
        deepLinkField.addActionListener { deepLinkButton.doClick() }
        controller.onResult { afterAction.restart() }
    }

    /** The device chosen for the project, which everything here acts on. */
    fun setDevice(connected: ConnectedDevice?) {
        device = connected
        refreshDevice()
        refreshApp()
        pushMessageRow.targetChanged()
    }

    /** The app chosen for the project changed, so what this says about it has. */
    fun setApp() {
        refreshApp()
        pushMessageRow.targetChanged()
    }

    /** The tool window was shown: the device may have been changed from anywhere meanwhile. */
    fun onShown() {
        refreshDevice()
        refreshApp()
    }

    /** Opens the dialog that chooses which actions are shown. */
    fun showActionSettings() {
        val service = AppSettingService.getInstance()
        val current = service.state
        val dialog = CheckBoxDialog(current.list) { changed ->
            val list = service.state.list.map {
                if (it.name == changed.name) it.copy(isSelected = changed.isSelected) else it
            }
            val next = service.state.copy(list = list)
            service.loadState(next)
            applySettings(next)
        }
        dialog.setLocationRelativeTo(null)
        dialog.pack()
        dialog.isVisible = true
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): JComponent = VerticallyScrollablePanel(ColumnsLayout()).apply {
        border = JBUI.Borders.empty(GAP)
        add(section("App", "home.app", appContent(), AllIcons.Nodes.Module))
        add(section("This screen", "home.screen", screenCard, AllIcons.General.InspectionsEye))
        permissionSection = section("Permissions", "home.permissions", permissionContent(), AllIcons.Actions.Lightning)
        add(permissionSection)
        add(section("Device", "home.device", deviceContent(), AllIcons.General.Settings, expanded = false))
    }

    private fun appContent(): JPanel = JPanel(BorderLayout()).apply {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, appActions(), true)
        toolbar.targetComponent = this@HomePanel
        add(toolbar.component, BorderLayout.NORTH)
        add(appInfoCard, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(GAP))).apply {
                add(ActionLink("Background work ›") { onBackgroundWork() })
            },
            BorderLayout.SOUTH,
        )
    }

    /**
     * Restart, debug, stop and process death in a row, and everything that destroys something
     * behind a menu: one click away from Restart was one click away from deleting the app's data.
     */
    private fun appActions() = DefaultActionGroup().apply {
        add(gated("RestartAppAction", SpockAction.RESTART))
        add(gated("RestartAppWithDebuggerAction", SpockAction.RESTART_DEBUG))
        add(gated("ForceStopAppAction", SpockAction.FORCE_KILL))
        add(gated("TestProcessDeathAction", SpockAction.TEST_PROCESS_DEATH))
        addSeparator()
        add(
            DefaultActionGroup("More App Actions", true).apply {
                templatePresentation.icon = AllIcons.Actions.More
                add(gated("ClearAppCacheAction", SpockAction.CLEAR_APP_CACHE))
                addSeparator()
                add(gated("ClearAppDataAction", SpockAction.CLEAR_APP_DATA))
                add(gated("ClearAppDataAndRestartAction", SpockAction.CLEAR_APP_DATA_RESTART))
                add(gated("UninstallAppAction", SpockAction.UNINSTALL))
            },
        )
    }

    private fun permissionContent(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        permissionSummary.alignmentX = LEFT_ALIGNMENT
        permissionLinks.alignmentX = LEFT_ALIGNMENT
        permissionLinks.add(ActionLink("Manage…") { managePermissions() })
        permissionLinks.add(ActionLink("Grant all") { grantAll() })
        permissionLinks.add(ActionLink("Revoke all…") { revokeAll() })
        add(permissionSummary)
        add(permissionLinks)
    }

    /** Everything about the device rather than the app, folded until it is wanted. */
    private fun deviceContent(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        listOf<JComponent>(wifiRow, mobileDataRow, httpProxyRow).forEach {
            it.alignmentX = LEFT_ALIGNMENT
            add(it)
        }
        developerSection = subheading("Developer options", developerOptions)
        add(developerSection)
        inputRow = fieldRow("Text", inputField, inputButton)
        deepLinkRow = fieldRow("Deep link", deepLinkField, deepLinkButton)
        add(inputRow)
        add(deepLinkRow)
        pushMessageRow.alignmentX = LEFT_ALIGNMENT
        add(pushMessageRow)
    }

    private fun subheading(title: String, content: JComponent) = JPanel(BorderLayout()).apply {
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(GAP * 2)
        add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
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

    private fun section(
        title: String,
        key: String,
        content: JComponent,
        icon: Icon,
        expanded: Boolean = true,
    ) = CollapsibleSection(title, content, key, expandedByDefault = expanded, icon = icon)

    // ---------------------------------------------------------------- settings

    /** A registered action, hidden while the settings dialog has [gate] switched off. */
    private fun gated(id: String, gate: SpockAction): AnAction {
        val action = ActionManager.getInstance().getAction("spock.adb.actions.$id")
        return object : AnActionWrapper(action) {
            override fun update(event: AnActionEvent) {
                super.update(event)
                if (!isOn(gate)) event.presentation.isEnabledAndVisible = false
            }
        }
    }

    /**
     * Records what the settings dialog has switched on and shows or hides the rows it governs.
     * Unknown entries are ignored: a renamed action must not stop the tool window opening.
     */
    private fun applySettings(setting: AppSetting) {
        enabled.clear()
        setting.list.forEach { item ->
            SpockAction.entries.firstOrNull { it.name == item.name.replace(" ", "_") }
                ?.let { enabled[it] = item.isSelected }
        }
        // Attaching a debugger needs Android Studio's execution tooling, absent in some IDEs.
        if (!DebuggerSupport.isAvailable) enabled[SpockAction.RESTART_DEBUG] = false

        appInfoCard.isVisible = isOn(SpockAction.APP_INFO)
        screenCard.setShown(
            activity = isOn(SpockAction.CURRENT_ACTIVITY),
            fragment = isOn(SpockAction.CURRENT_FRAGMENT),
            stacks = isOn(SpockAction.CURRENT_APP_STACK) || isOn(SpockAction.BACK_STACK),
        )
        permissionSection.setSectionVisible(isOn(SpockAction.PERMISSIONS))
        wifiRow.isVisible = isOn(SpockAction.TOGGLE_NETWORK)
        mobileDataRow.isVisible = isOn(SpockAction.TOGGLE_NETWORK)
        httpProxyRow.setActionVisible(isOn(SpockAction.HTTP_PROXY))
        httpProxyRow.isVisible = isOn(SpockAction.HTTP_PROXY)
        developerSection.isVisible = isOn(SpockAction.DEVELOPER_OPTIONS)
        inputRow.isVisible = isOn(SpockAction.INPUT)
        deepLinkRow.isVisible = isOn(SpockAction.DEEP_LINK)
        pushMessageRow.isVisible = isOn(SpockAction.PUSH_MESSAGE)
        revalidate()
        repaint()
    }

    private fun isOn(action: SpockAction): Boolean = enabled[action] ?: true

    // ---------------------------------------------------------------- reads

    /** The device's own state: network, proxy, developer options. */
    private fun refreshDevice() {
        httpProxyRow.refresh()
        wifiRow.refresh()
        mobileDataRow.refresh()
        developerOptions.refresh()
    }

    /** The app's state and the screen it is on. */
    private fun refreshApp() {
        appInfoCard.refresh()
        screenCard.refresh()
        refreshPermissionSummary()
    }

    private fun refreshPermissionSummary() {
        // Taken before the early return, so a refresh that reads nothing still retires one in
        // flight rather than letting its answer land on a line about another app.
        val request = permissionReads.begin()
        val target = device
        if (target == null || !isOn(SpockAction.PERMISSIONS) || !::controller.isInitialized) {
            permissionSummary.text = " "
            return
        }
        permissionSummary.text = "Reading permissions…"
        controller.permissionSummary(target.device) { result ->
            if (permissionReads.isLatest(request)) permissionSummary.text = result.getOrNull()?.describe() ?: " "
        }
    }

    // ---------------------------------------------------------------- permissions

    private fun managePermissions() {
        val target = device ?: return
        controller.getApplicationPermissions(target.device) { list ->
            val dialog = CheckBoxDialog(list) { item ->
                // Every change is read back: the count above is about to be wrong.
                if (item.isSelected) {
                    controller.grantPermission(target.device, item) { refreshPermissionSummary() }
                } else {
                    controller.revokePermission(target.device, item) { refreshPermissionSummary() }
                }
            }
            dialog.pack()
            dialog.isVisible = true
        }
    }

    private fun grantAll() {
        val target = device ?: return
        controller.grantOrRevokeAllPermissions(target.device, GetApplicationPermission.PermissionOperation.GRANT) {
            refreshPermissionSummary()
        }
    }

    private fun revokeAll() {
        val target = device ?: return
        if (!DestructiveActionConfirmation.confirmRevokeAllPermissions(project, target.info)) return
        controller.grantOrRevokeAllPermissions(target.device, GetApplicationPermission.PermissionOperation.REVOKE) {
            refreshPermissionSummary()
        }
    }

    private companion object {
        const val GAP = 4
        const val AFTER_ACTION_MS = 1200
    }
}
