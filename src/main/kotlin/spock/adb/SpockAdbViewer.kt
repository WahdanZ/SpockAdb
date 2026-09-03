package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import spock.adb.command.*
import spock.adb.compat.DebuggerSupport
import spock.adb.premission.CheckBoxDialog
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import javax.swing.*

class SpockAdbViewer(
    private val project: Project,
    /** Scopes the message bus connection; without a parent the connection is never released. */
    private val parentDisposable: Disposable,
) : SimpleToolWindowPanel(true) {
    private lateinit var rootPanel: JPanel
    private lateinit var permissionPanel: JPanel
    private lateinit var networkPanel: JPanel
    private lateinit var developerPanel: JPanel
    private lateinit var devicesListComboBox: JComboBox<String>
    private lateinit var currentActivityButton: JButton
    private lateinit var currentFragmentButton: JButton
    private lateinit var clearAppDataButton: JButton
    private lateinit var clearAppDataAndRestartButton: JButton
    private lateinit var uninstallAppButton: JButton
    private lateinit var permissionButton: JButton
    private lateinit var grantAllPermissionsButton: JButton
    private lateinit var revokeAllPermissionsButton: JButton
    private lateinit var restartAppButton: JButton
    private lateinit var restartAppWithDebuggerButton: JButton
    private lateinit var forceKillAppButton: JButton
    private lateinit var testProcessDeathButton: JButton
    private lateinit var activitiesBackStackButton: JButton
    private lateinit var currentAppBackStackButton: JButton
    private lateinit var adbWifi: JButton
    private lateinit var setting: JButton
    private var devices: List<IDevice> = emptyList()
    private lateinit var enableDisableDontKeepActivities: JCheckBox
    private lateinit var enableDisableShowTaps: JCheckBox
    private lateinit var enableDisableShowLayoutBounds: JCheckBox
    private lateinit var windowAnimatorScaleComboBox: JComboBox<String>
    private lateinit var transitionAnimatorScaleComboBox: JComboBox<String>
    private lateinit var animatorDurationScaleComboBox: JComboBox<String>
    private lateinit var wifiToggle: JButton
    private lateinit var mobileDataToggle: JButton
    private lateinit var inputOnDeviceTextField: JTextField
    private lateinit var openDeepLinkTextField: JTextField
    private lateinit var inputOnDeviceButton: JButton
    private lateinit var openDeepLinkButton: JButton
    private lateinit var openDeveloperOptionsButton: JButton
    private var selectedIDevice: IDevice? = null

    private lateinit var adbController: AdbController

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
        // The IntelliJ form compiler generates $$$setupUI$$$() but does not inject the call
        // into Kotlin constructors (unlike Java). We must invoke it explicitly via reflection.
        javaClass.getDeclaredMethod("\$\$\$setupUI\$\$\$").invoke(this)
        setContent(JScrollPane(rootPanel))
        setToolWindowListener()
        AppSettingService.getInstance().run {
            updateUi(state)
        }
    }

    fun initPlugin(adbController: AdbController) {
        this.adbController = adbController

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
                selectedIDevice = devices.getOrNull(devicesListComboBox.selectedIndex)
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
            selectedIDevice?.let { device ->
                adbController.clearAppData(device)
            }
        }
        clearAppDataAndRestartButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.clearAppDataAndRestart(device)
            }
        }
        uninstallAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.uninstallApp(device)
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
            selectedIDevice?.let { device ->
                adbController.grantOrRevokeAllPermissions(
                    device,
                    GetApplicationPermission.PermissionOperation.REVOKE,

                    )
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
                SpockAction.TOGGLE_NETWORK -> networkPanel.isVisible = it.isSelected
                SpockAction.PERMISSIONS -> permissionPanel.isVisible = it.isSelected
                SpockAction.DEVELOPER_OPTIONS -> developerPanel.isVisible = it.isSelected
                SpockAction.INPUT -> {
                    inputOnDeviceButton.isVisible = it.isSelected
                    inputOnDeviceTextField.isVisible = it.isSelected
                }
                SpockAction.DEEP_LINK -> {
                    openDeepLinkButton.isVisible = it.isSelected
                    openDeepLinkTextField.isVisible = it.isSelected
                }
            }
            rootPanel.invalidate()
        }
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
            val previousSerial = selectedIDevice?.serialNumber
            selectedIDevice = connected.firstOrNull { it.serialNumber == previousSerial }
                ?: connected.firstOrNull()

            devicesListComboBox.model = DefaultComboBoxModel(
                connected.map { it.name }.toTypedArray(),
            )
            selectedIDevice?.let { devicesListComboBox.selectedIndex = connected.indexOf(it) }
        }
    }

    private fun removeDeveloperOptionsListeners() {
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
    }
}
