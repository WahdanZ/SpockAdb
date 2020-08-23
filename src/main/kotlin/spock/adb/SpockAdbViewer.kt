package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import spock.adb.command.*
import spock.adb.premission.PermissionDialog
import java.awt.event.ActionEvent
import javax.swing.*

class SpockAdbViewer(
    private val project: Project
) : SimpleToolWindowPanel(true) {
    private lateinit var panel1: JPanel
    private lateinit var rootPanel: JPanel
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
    private lateinit var adbWifi: JButton
    private lateinit var wifiDebug: JButton
    private lateinit var devices: List<IDevice>
    private lateinit var enableDisableDontKeepActivities: JCheckBox
    private lateinit var enableDisableShowTaps: JCheckBox
    private lateinit var enableDisableShowLayoutBounds: JCheckBox
    private lateinit var windowAnimatorScaleComboBox: JComboBox<String>
    private lateinit var transitionAnimatorScaleComboBox: JComboBox<String>
    private lateinit var animatorDurationScaleComboBox: JComboBox<String>
    private var selectedIDevice: IDevice? = null
    private val notifier: NotificationGroup by lazy {
        NotificationGroup("Spock_ADB",
            NotificationDisplayType.BALLOON, true)
    }

    private lateinit var adbController: AdbController

    private val dontKeepActivitiesActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.enableDisableDontKeepActivities(device, ::showSuccess, ::showError)
        }
    }

    private val showTapsActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.enableDisableShowTaps(device, ::showSuccess, ::showError)
        }
    }

    private val showLayoutBoundsActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.enableDisableShowLayoutBounds(device, ::showSuccess, ::showError)
            device.refreshUi()
        }
    }

    private val windowAnimatorScaleActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.setWindowAnimatorScale(
                windowAnimatorScaleComboBox.selectedItem as String,
                device,
                ::showSuccess,
                ::showError
            )
        }
    }

    private val transitionAnimatorScaleActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.setTransitionAnimatorScale(
                transitionAnimatorScaleComboBox.selectedItem as String,
                device,
                ::showSuccess,
                ::showError
            )
        }
    }

    private val animatorDurationScaleActionListener: (ActionEvent) -> Unit = {
        selectedIDevice?.let { device ->
            adbController.setAnimatorDurationScale(
                animatorDurationScaleComboBox.selectedItem as String,
                device,
                ::showSuccess,
                ::showError
            )
        }
    }

    init {
        setContent(rootPanel)
        setToolWindowListener()
    }

    fun initPlugin(adbController: AdbController) {
        this.adbController = adbController

        updateDevicesList()

        wifiDebug.isEnabled = false
        wifiDebug.isVisible = false
        val deviceSelected = { x:Boolean->
            wifiDebug.isEnabled = x
        }
        adbWifi.isVisible = false
        adbWifi.addActionListener{
            val ip = Messages.showInputDialog(
                "Enter You Android Device IP address",
                "Spock Adb- Device connect over Wifi",
                null,
                "192.168.1.20",
                IPAddressInputValidator()
            )
            ip?.let { adbController.connectDeviceOverIp(ip = ip, success = ::showSuccess, error = ::showError) }

        }

//        refresh.addActionListener {
//            adbController.refresh()
//            updateDevicesList()
//        }
        devicesListComboBox.addItemListener {
            selectedIDevice = devices[devicesListComboBox.selectedIndex]
            deviceSelected(true)
        }
        activitiesBackStackButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentBackStack(device, ::showSuccess, ::showError)
            }
        }
        currentActivityButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentActivity(device, ::showSuccess, ::showError)
            }
        }
        currentFragmentButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentFragment(device, ::showSuccess, ::showError)
            }
        }
        restartAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.restartApp(device, ::showSuccess, ::showError)
            }
        }
        restartAppWithDebuggerButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.restartAppWithDebugger(device, ::showSuccess, ::showError)
            }
        }
        forceKillAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.forceKillApp(device, ::showSuccess, ::showError)
            }
        }
        testProcessDeathButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.testProcessDeath(device, ::showSuccess, ::showError)
            }
        }
        clearAppDataButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.clearAppData(device, ::showSuccess, ::showError)
            }
        }
        clearAppDataAndRestartButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.clearAppDataAndRestart(device, ::showSuccess, ::showError)
            }
        }
        uninstallAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.uninstallApp(device, ::showSuccess, ::showError)
            }
        }

        permissionButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.getApplicationPermissions(device, {
                    val dialog = PermissionDialog(device, adbController, it)
                    dialog.pack()
                    dialog.isVisible = true

                }, ::showError)
            }
        }
        grantAllPermissionsButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.grantOrRevokeAllPermissions(
                    device,
                    GetApplicationPermission.PermissionOperation.GRANT,
                    ::showSuccess,
                    ::showError
                )
            }
        }
        revokeAllPermissionsButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.grantOrRevokeAllPermissions(
                    device,
                    GetApplicationPermission.PermissionOperation.REVOKE,
                    ::showSuccess,
                    ::showError
                )
            }
        }
    }

    private fun updateDevicesList() {
        adbController.connectedDevices({ devices ->
            this.devices = devices
            selectedIDevice = this.devices.getOrElse(devices.indexOf(selectedIDevice)) { this.devices.getOrNull(0) }
            devicesListComboBox.model = DefaultComboBoxModel<String>(
                devices.map { device ->
                    device.name
                }.toTypedArray()
            )
        }, ::showError)
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
        enableDisableDontKeepActivities.addActionListener(dontKeepActivitiesActionListener)

        enableDisableShowTaps.addActionListener(showTapsActionListener)

        enableDisableShowLayoutBounds.addActionListener(showLayoutBoundsActionListener)

        windowAnimatorScaleComboBox.addActionListener(windowAnimatorScaleActionListener)

        transitionAnimatorScaleComboBox.addActionListener(transitionAnimatorScaleActionListener)

        animatorDurationScaleComboBox.addActionListener(animatorDurationScaleActionListener)
    }

    private fun showError(message: String) {
        notifier.createNotification(
            "Spock ADB",
            message,
            NotificationType.ERROR,
            null
        ).notify(project)
    }

    private fun showSuccess(message: String) {
        notifier.createNotification(
            "Spock ADB",
            message,
            NotificationType.INFORMATION,
            null
        ).notify(project)
    }

    private fun setToolWindowListener() {
        ToolWindowManager
            .getInstance(project)
            .run {
                val toolWindow = getToolWindow("Spock ADB")

                if (toolWindow != null) {
                    (this as? ToolWindowManagerEx)?.addToolWindowManagerListener(object : ToolWindowManagerListener {
                        override fun stateChanged() {
                            super.stateChanged()
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
