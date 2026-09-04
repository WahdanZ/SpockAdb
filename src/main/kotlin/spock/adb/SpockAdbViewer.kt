package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import spock.adb.command.*
import spock.adb.compat.DebuggerSupport
import spock.adb.device.ConnectedDevice
import spock.adb.premission.CheckBoxDialog
import spock.adb.ui.CollapsibleSection
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
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
    private val devicesListComboBox = JComboBox<String>()
    private val setting = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Choose which actions are shown"
    }

    private val currentActivityButton = JButton("Current Activity")
    private val currentFragmentButton = JButton("Current Fragment")
    private val currentAppBackStackButton = JButton("App Back Stack")
    private val activitiesBackStackButton = JButton("All Activities")

    private val restartAppButton = JButton("Restart")
    private val restartAppWithDebuggerButton = JButton("Restart + Debugger")
    private val forceKillAppButton = JButton("Force Stop")
    private val testProcessDeathButton = JButton("Process Death")

    // Destructive actions carry an ellipsis: they open a confirmation rather than acting.
    private val clearAppDataButton = JButton("Clear Data...")
    private val clearAppDataAndRestartButton = JButton("Clear Data & Restart...")
    private val uninstallAppButton = JButton("Uninstall...")

    private val permissionButton = JButton("Manage...")
    private val grantAllPermissionsButton = JButton("Grant All")
    private val revokeAllPermissionsButton = JButton("Revoke All...")

    private val openDeveloperOptionsButton = JButton("Open on Device")
    private val enableDisableDontKeepActivities = JCheckBox("Don't keep activities")
    private val enableDisableShowTaps = JCheckBox("Show taps")
    private val enableDisableShowLayoutBounds = JCheckBox("Show layout bounds")
    private val windowAnimatorScaleComboBox = JComboBox(ANIMATION_SCALES)
    private val transitionAnimatorScaleComboBox = JComboBox(ANIMATION_SCALES)
    private val animatorDurationScaleComboBox = JComboBox(ANIMATION_SCALES)

    private val wifiToggle = JButton("Wi-Fi")
    private val mobileDataToggle = JButton("Mobile Data")

    private val inputOnDeviceTextField = JBTextField()
    private val inputOnDeviceButton = JButton("Send")
    private val openDeepLinkTextField = JBTextField()
    private val openDeepLinkButton = JButton("Open")

    /** Kept only so the hidden, unimplemented "connect over IP" control still resolves. */
    private val adbWifi = JButton()

    private var devices: List<ConnectedDevice> = emptyList()

    // Sections, so a group whose actions are all switched off hides its heading too.
    private lateinit var navigateSection: CollapsibleSection
    private lateinit var lifecycleSection: CollapsibleSection
    private lateinit var dangerSection: CollapsibleSection
    private lateinit var permissionSection: CollapsibleSection
    private lateinit var developerSection: CollapsibleSection
    private lateinit var networkSection: CollapsibleSection
    private lateinit var sendSection: CollapsibleSection
    private var selectedDevice: ConnectedDevice? = null
        set(value) {
            field = value
            deviceListeners.forEach { it(value) }
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

    private val dontKeepActivitiesActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.enableDisableDontKeepActivities(device)
        }
    }

    private val showTapsActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.enableDisableShowTaps(device)
        }
    }

    private val showLayoutBoundsActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.enableDisableShowLayoutBounds(device)
            device.refreshUi()
        }
    }

    private val windowAnimatorScaleActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.setWindowAnimatorScale(
                windowAnimatorScaleComboBox.selectedItem as String,
                device

            )
        }
    }

    private val transitionAnimatorScaleActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.setTransitionAnimatorScale(
                transitionAnimatorScaleComboBox.selectedItem as String,
                device

            )
        }
    }

    private val animatorDurationScaleActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.setAnimatorDurationScale(
                animatorDurationScaleComboBox.selectedItem as String,
                device
            )
        }
    }

    init {
        setContent(JScrollPane(buildLayout()).apply { border = JBUI.Borders.empty() })
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
    private fun buildLayout(): JPanel {
        navigateSection = section(
            "Navigate",
            "navigate",
            grid(currentActivityButton, currentFragmentButton, currentAppBackStackButton, activitiesBackStackButton),
        )
        lifecycleSection = section(
            "App lifecycle",
            "lifecycle",
            grid(restartAppButton, restartAppWithDebuggerButton, forceKillAppButton, testProcessDeathButton),
        )
        dangerSection = section(
            "Destructive",
            "destructive",
            grid(clearAppDataButton, clearAppDataAndRestartButton, uninstallAppButton),
        )
        permissionSection = section(
            "Permissions",
            "permissions",
            grid(permissionButton, grantAllPermissionsButton, revokeAllPermissionsButton),
        )
        developerSection = section("Developer options", "developer", developerOptionsContent())
        networkSection = section("Network", "network", grid(wifiToggle, mobileDataToggle))
        sendSection = section("Send to device", "send", sendContent())

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(GAP)
            add(deviceRow())
            listOf(
                navigateSection,
                lifecycleSection,
                dangerSection,
                permissionSection,
                developerSection,
                networkSection,
                sendSection,
            ).forEach { add(it) }
            // Absorbs the slack so the sections stay at the top instead of stretching.
            add(Box.createVerticalGlue())
        }
        return content
    }

    private fun deviceRow(): JPanel = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        add(devicesListComboBox, BorderLayout.CENTER)
        add(setting, BorderLayout.EAST)
    }

    /** Two buttons per row; an odd count leaves the last one on its own row. */
    private fun grid(vararg buttons: JButton): JPanel = JPanel(
        GridLayout(0, COLUMNS, JBUI.scale(GAP), JBUI.scale(GAP)),
    ).apply {
        border = JBUI.Borders.empty(GAP, 0)
        buttons.forEach { add(it) }
        if (buttons.size % COLUMNS != 0) add(JPanel())
    }

    private fun developerOptionsContent(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        add(leftAligned(openDeveloperOptionsButton))
        add(leftAligned(enableDisableDontKeepActivities))
        add(leftAligned(enableDisableShowTaps))
        add(leftAligned(enableDisableShowLayoutBounds))
        add(scaleRow("Window animation", windowAnimatorScaleComboBox))
        add(scaleRow("Transition animation", transitionAnimatorScaleComboBox))
        add(scaleRow("Animator duration", animatorDurationScaleComboBox))
    }

    private fun scaleRow(label: String, combo: JComboBox<String>): JPanel =
        JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, combo.preferredSize.height + JBUI.scale(GAP))
            border = JBUI.Borders.emptyTop(2)
            add(JBLabel(label), BorderLayout.WEST)
            add(combo, BorderLayout.EAST)
        }

    /** Field plus its action button, so the text and what it does stay adjacent. */
    private fun sendContent(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(GAP, 0)
        add(fieldRow("Text", inputOnDeviceTextField, inputOnDeviceButton))
        add(fieldRow("Deep link", openDeepLinkTextField, openDeepLinkButton))
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

    private fun leftAligned(component: JComponent): JPanel =
        JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, JBUI.scale(2))).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, component.preferredSize.height + JBUI.scale(GAP))
            add(component)
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
        wifiToggle.addActionListener {
            selectedIDevice?.let { device ->
                adbController.toggleNetwork(device, Network.WIFI)
            }
        }
        mobileDataToggle.addActionListener {
            selectedIDevice?.let { device ->
                adbController.toggleNetwork(device, Network.MOBILE)
            }
        }
        inputOnDeviceButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.inputOnDevice(inputOnDeviceTextField.text, device)
            }
        }
        inputOnDeviceTextField.addActionListener { inputOnDeviceButton.doClick() }
        openDeveloperOptionsButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.openDeveloperOptions(device)
            }
        }
        openDeepLinkButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.openDeepLink(openDeepLinkTextField.text, device)
            }
        }
        openDeepLinkTextField.addActionListener { openDeepLinkButton.doClick() }
    }

    private fun updateUi(it: AppSetting) {
        it.list.forEach {
            // Settings persist action names as text. An action that is renamed or removed
            // leaves a stale entry behind, and SpockAction.valueOf then threw
            // IllegalArgumentException from the constructor — which prevented the tool
            // window from opening at all. Unknown entries are now ignored.
            val action = SpockAction.entries.firstOrNull { action ->
                action.name == it.name.replace(" ", "_")
            } ?: return@forEach

            when (action) {
                SpockAction.CURRENT_ACTIVITY -> currentActivityButton.isVisible = it.isSelected
                SpockAction.CURRENT_FRAGMENT -> currentFragmentButton.isVisible = it.isSelected
                SpockAction.CURRENT_APP_STACK -> currentAppBackStackButton.isVisible = it.isSelected
                SpockAction.BACK_STACK -> activitiesBackStackButton.isVisible = it.isSelected
                SpockAction.CLEAR_APP_DATA -> clearAppDataButton.isVisible = it.isSelected
                SpockAction.CLEAR_APP_DATA_RESTART -> clearAppDataAndRestartButton.isVisible = it.isSelected
                SpockAction.RESTART -> restartAppButton.isVisible = it.isSelected
                // Attaching a debugger needs the Android Studio execution tooling, which is
                // absent in some IDEs that bundle the Android plugin. Hide the action there
                // rather than offering a button that can only report an error.
                SpockAction.RESTART_DEBUG ->
                    restartAppWithDebuggerButton.isVisible = it.isSelected && DebuggerSupport.isAvailable
                SpockAction.TEST_PROCESS_DEATH -> testProcessDeathButton.isVisible = it.isSelected
                SpockAction.FORCE_KILL -> forceKillAppButton.isVisible = it.isSelected
                SpockAction.UNINSTALL -> uninstallAppButton.isVisible = it.isSelected
                SpockAction.TOGGLE_NETWORK -> networkSection.setSectionVisible(it.isSelected)
                SpockAction.PERMISSIONS -> permissionSection.setSectionVisible(it.isSelected)
                SpockAction.DEVELOPER_OPTIONS -> developerSection.setSectionVisible(it.isSelected)
                SpockAction.INPUT -> {
                    inputOnDeviceButton.isVisible = it.isSelected
                    inputOnDeviceTextField.isVisible = it.isSelected
                }
                SpockAction.DEEP_LINK -> {
                    openDeepLinkButton.isVisible = it.isSelected
                    openDeepLinkTextField.isVisible = it.isSelected
                }
            }
        }
        refreshSectionVisibility()
    }

    /**
     * Hides a section heading when every action inside it has been switched off, so the
     * settings dialog cannot leave an empty titled separator behind.
     */
    private fun refreshSectionVisibility() {
        navigateSection.setSectionVisible(
            listOf(
                currentActivityButton,
                currentFragmentButton,
                currentAppBackStackButton,
                activitiesBackStackButton,
            ).any { it.isVisible },
        )
        lifecycleSection.setSectionVisible(
            listOf(
                restartAppButton,
                restartAppWithDebuggerButton,
                forceKillAppButton,
                testProcessDeathButton,
            ).any { it.isVisible },
        )
        dangerSection.setSectionVisible(
            listOf(clearAppDataButton, clearAppDataAndRestartButton, uninstallAppButton).any { it.isVisible },
        )
        sendSection.setSectionVisible(
            inputOnDeviceButton.isVisible || openDeepLinkButton.isVisible,
        )
        revalidate()
        repaint()
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
                devicesListComboBox.toolTipText =
                    "Connect a device or start an emulator, then press Refresh. " +
                        "If a device is attached, check idea.log for ADB errors."
            } else {
                devicesListComboBox.isEnabled = true
                devicesListComboBox.model = DefaultComboBoxModel(
                    connected.map { it.info.label() }.toTypedArray(),
                )
                selectedDevice?.let { devicesListComboBox.selectedIndex = connected.indexOf(it) }
                devicesListComboBox.toolTipText = selectedDevice?.info?.describe()
            }
            rememberSelectedDevice()
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

    private fun removeDeveloperOptionsListeners() {
        enableDisableDontKeepActivities.actionListeners.forEach {
            enableDisableDontKeepActivities.removeActionListener(it)
        }

        enableDisableShowTaps.actionListeners.forEach {
            enableDisableShowTaps.removeActionListener(it)
        }

        enableDisableShowLayoutBounds.actionListeners.forEach {
            enableDisableShowLayoutBounds.removeActionListener(it)
        }

        windowAnimatorScaleComboBox.actionListeners.forEach {
            windowAnimatorScaleComboBox.removeActionListener(it)
        }

        transitionAnimatorScaleComboBox.actionListeners.forEach {
            transitionAnimatorScaleComboBox.removeActionListener(it)
        }

        animatorDurationScaleComboBox.actionListeners.forEach {
            animatorDurationScaleComboBox.removeActionListener(it)
        }
    }

    private fun setDeveloperOptionsValues() {
        // Read ADB values on the current (background) thread
        val dontKeepActivities = selectedIDevice?.areDontKeepActivitiesEnabled()
        val showTaps = selectedIDevice?.areShowTapsEnabled()
        val showLayoutBounds = selectedIDevice?.areShowLayoutBoundsEnabled()
        val windowScale = selectedIDevice?.getWindowAnimatorScale()
        val transitionScale = selectedIDevice?.getTransitionAnimationScale()
        val durationScale = selectedIDevice?.getAnimatorDurationScale()

        // Apply to UI components and re-add listeners on the EDT
        ApplicationManager.getApplication().invokeLater {
            removeDeveloperOptionsListeners()
            enableDisableDontKeepActivities.isSelected = dontKeepActivities == DontKeepActivitiesState.ENABLED
            enableDisableShowTaps.isSelected = showTaps == ShowTapsState.ENABLED
            enableDisableShowLayoutBounds.isSelected = showLayoutBounds == ShowLayoutBoundsState.ENABLED
            windowAnimatorScaleComboBox.selectedItem = WindowAnimatorScaleCommand.getWindowAnimatorScaleIndex(windowScale)
            transitionAnimatorScaleComboBox.selectedItem = TransitionAnimatorScaleCommand.getTransitionAnimatorScaleIndex(transitionScale)
            animatorDurationScaleComboBox.selectedItem = AnimatorDurationScaleCommand.getAnimatorDurationScaleIndex(durationScale)
            setDeveloperOptionsListeners()
        }
    }

    private fun setDeveloperOptionsListeners() {
        enableDisableDontKeepActivities.addActionListener(dontKeepActivitiesActionListener)

        enableDisableShowTaps.addActionListener(showTapsActionListener)

        enableDisableShowLayoutBounds.addActionListener(showLayoutBoundsActionListener)

        windowAnimatorScaleComboBox.addActionListener(windowAnimatorScaleActionListener)

        transitionAnimatorScaleComboBox.addActionListener(transitionAnimatorScaleActionListener)

        animatorDurationScaleComboBox.addActionListener(animatorDurationScaleActionListener)
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

                        removeDeveloperOptionsListeners()
                        ApplicationManager.getApplication().executeOnPooledThread {
                            setDeveloperOptionsValues()
                        }
                    }
                },
            )
    }

    private companion object {
        const val TOOL_WINDOW_ID = "Spock ADB"
        const val GAP = 4
        const val COLUMNS = 2
        val ANIMATION_SCALES = arrayOf("0.0", "0.5", "1.0", "1.5", "2.0", "5.0", "10.0")
        const val NO_DEVICES_LABEL = "No devices connected"
    }
}
