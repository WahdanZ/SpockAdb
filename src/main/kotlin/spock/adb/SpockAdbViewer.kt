package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import spock.adb.command.*
import spock.adb.premission.CheckBoxDialog
import java.awt.event.ActionEvent
import javax.swing.*

class SpockAdbViewer(
    private val project: Project
) : SimpleToolWindowPanel(true) {
    private lateinit var panel1: JPanel
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
    private lateinit var refresh: JButton
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
    private lateinit var devices: List<IDevice>
    private lateinit var enableDisableDontKeepActivities: JCheckBox
    private lateinit var enableDisableShowTaps: JCheckBox
    private lateinit var enableDisableShowLayoutBounds: JCheckBox
    private lateinit var windowAnimatorScaleComboBox: JComboBox<String>
    private lateinit var transitionAnimatorScaleComboBox: JComboBox<String>
    private lateinit var animatorDurationScaleComboBox: JComboBox<String>
    private lateinit var wifiToggle: JButton
    private lateinit var mobileDataToggle: JButton
    private lateinit var inputOnDeviceTextField: JTextField
    private lateinit var inputOnDeviceButton: JButton
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
        setContent(JScrollPane(rootPanel))
        setToolWindowListener()
        AppSettingService.getInstance().run {
            state?.let {
                updateUi(it)
            }
        }
    }

    fun initPlugin(adbController: AdbController) {
        this.adbController = adbController

        updateDevicesList()

        setting.isEnabled = true
        setting.isVisible = true
        setting.addActionListener {
            AppSettingService.getInstance().run {
                state?.let {
                    val dialog = CheckBoxDialog(it.list) { selectedItem ->
                        println(selectedItem)
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
        adbWifi.addActionListener {
            val ip = Messages.showInputDialog(
                "Enter You Android Device IP address",
                "Spock Adb- Device connect over Wifi",
                null,
                "192.168.1.20",
                IPAddressInputValidator()
            )
            ip?.let { adbController.connectDeviceOverIp(ip = ip) }

        }

//        refresh.addActionListener {
//            adbController.refresh()
//            updateDevicesList()
//        }
        devicesListComboBox.addItemListener {
            selectedIDevice = devices[devicesListComboBox.selectedIndex]

        }
        setting.addActionListener {

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
    }

    private fun updateUi(it: AppSetting) {
        it.list.map {
            when (SpockAction.valueOf(it.name.replace(" ", "_"))) {
                SpockAction.CURRENT_ACTIVITY -> currentActivityButton.isVisible = it.isSelected
                SpockAction.CURRENT_FRAGMENT -> currentFragmentButton.isVisible = it.isSelected
                SpockAction.CURRENT_APP_STACK -> currentAppBackStackButton.isVisible = it.isSelected
                SpockAction.BACK_STACK -> activitiesBackStackButton.isVisible = it.isSelected
                SpockAction.CLEAR_APP_DATA -> clearAppDataButton.isVisible = it.isSelected
                SpockAction.CLEAR_APP_DATA_RESTART -> clearAppDataAndRestartButton.isVisible = it.isSelected
                SpockAction.RESTART -> restartAppButton.isVisible = it.isSelected
                SpockAction.RESTART_DEBUG -> restartAppWithDebuggerButton.isVisible = it.isSelected
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
            }
            rootPanel.invalidate()
        }
    }

    private fun updateDevicesList() {
        adbController.connectedDevices { devices ->
            this.devices = devices
            selectedIDevice = this.devices.getOrElse(devices.indexOf(selectedIDevice)) { this.devices.getOrNull(0) }

            devicesListComboBox.model = DefaultComboBoxModel(
                devices.map { device ->
                    device.name
                }.toTypedArray()
            )
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
        enableDisableDontKeepActivities.isSelected =
            selectedIDevice?.areDontKeepActivitiesEnabled() == DontKeepActivitiesState.ENABLED

        enableDisableShowTaps.isSelected = selectedIDevice?.areShowTapsEnabled() == ShowTapsState.ENABLED

        enableDisableShowLayoutBounds.isSelected =
            selectedIDevice?.areShowLayoutBoundsEnabled() == ShowLayoutBoundsState.ENABLED

        windowAnimatorScaleComboBox.selectedItem =
            WindowAnimatorScaleCommand.getWindowAnimatorScaleIndex(selectedIDevice?.getWindowAnimatorScale())

        transitionAnimatorScaleComboBox.selectedItem =
            TransitionAnimatorScaleCommand.getTransitionAnimatorScaleIndex(selectedIDevice?.getTransitionAnimationScale())

        animatorDurationScaleComboBox.selectedItem =
            AnimatorDurationScaleCommand.getAnimatorDurationScaleIndex(selectedIDevice?.getAnimatorDurationScale())
    }

    private fun setDeveloperOptionsListeners() {
        enableDisableShowTaps.addActionListener(showTapsActionListener)

        enableDisableShowLayoutBounds.addActionListener(showLayoutBoundsActionListener)

        windowAnimatorScaleComboBox.addActionListener(windowAnimatorScaleActionListener)

        transitionAnimatorScaleComboBox.addActionListener(transitionAnimatorScaleActionListener)

        animatorDurationScaleComboBox.addActionListener(animatorDurationScaleActionListener)
    }

    private fun setToolWindowListener() {

        ToolWindowManager
            .getInstance(project)
            .run {
                val toolWindow = getToolWindow("Spock ADB")
                if (toolWindow != null) {
                    project.messageBus.connect()
                        .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                            override fun stateChanged() {
                                if (toolWindow.isVisible) {
                                    removeDeveloperOptionsListeners()
                                    setDeveloperOptionsValues()
                                    setDeveloperOptionsListeners()
                                }
                            }
                        })
                }
            }
    }
}
