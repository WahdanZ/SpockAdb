package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import spock.adb.premission.PermissionDialog
import javax.swing.*
import com.intellij.openapi.ui.Messages

class SpockAdbViewer(
    private val adbController: AdbController,
    private val project: Project
) : SimpleToolWindowPanel(true) {
    private lateinit var panel1: JPanel
    private lateinit var rootPanel: JPanel
    private lateinit var devicesListComboBox: JComboBox<String>
    private lateinit var currentActivityButton: JButton
    private lateinit var currentFragmentButton: JButton
    private lateinit var clearAppDataButton: JButton
    private lateinit var refresh: JButton
    private lateinit var permissionButton: JButton
    private lateinit var restartAppButton: JButton
    private lateinit var killAppButton: JButton
    private lateinit var activitiesBackStackButton: JButton
    private lateinit var adbWifi: JButton
    private lateinit var wifiDebug: JButton
    private lateinit var devices: List<IDevice>
    private var selectedIDevice: IDevice? = null
    private val notifier: NotificationGroup by lazy {
        NotificationGroup("Spock_ADB",
            NotificationDisplayType.BALLOON, true)
    }


    init {
        setContent(rootPanel)
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
        killAppButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.killApp(device, ::showSuccess, ::showError)
            }
        }
        clearAppDataButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.clearAppData(device, ::showSuccess, ::showError)
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

}










