package spock.adb
import com.android.ddmlib.IDevice
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.ui.SimpleToolWindowPanel
import javax.swing.*
import spock.adb.premission.PermissionDialog


class SpockAdbViewer(private val  adbController: AdbController
) : SimpleToolWindowPanel(true) {
    private lateinit var panel1: JPanel
    private lateinit var rootPanel: JPanel
    private lateinit var devicesListComboBox: JComboBox<String>
    private lateinit var currentActivityButton: JButton
    private lateinit var currentFragmentButton: JButton
    private lateinit var clearAppDataButton: JButton
    private lateinit var premissionButton: JButton
    private lateinit var restartAppButton: JButton
    private lateinit var killAppButton: JButton
    private lateinit var errorMessageLable: JLabel
    private lateinit var devices: List<IDevice>
    private var selectedIDevice: IDevice? = null

    init {
        setContent(rootPanel)
        updateDevicesList()

        devicesListComboBox.addItemListener {
            selectedIDevice = devices[devicesListComboBox.selectedIndex]
        }

        currentActivityButton.addActionListener {
            selectedIDevice?.let { device ->
                adbController.currentActivity(device,::showSuccess,::showError)
            }
        }
        currentFragmentButton.addActionListener {
            selectedIDevice?.let {device->
                adbController.currentFragment(device,::showSuccess,::showError)
            }
        }
        restartAppButton.addActionListener {
            selectedIDevice?.let {device->
                adbController.restartApp(device,::showSuccess,::showError)
            }
        }
        killAppButton.addActionListener {
            selectedIDevice?.let {device->
                adbController.killApp(device,::showSuccess,::showError)
            }
        }
        clearAppDataButton.addActionListener {
            selectedIDevice?.let {device->
                adbController.clearAppData(device,::showSuccess,::showError)
            }
        }
        premissionButton.addActionListener { _ ->
            selectedIDevice?.let {device->
               adbController.getApplicationPermissions(device,{
                   val dialog = PermissionDialog(device,adbController,it)
                   dialog.pack()
                   dialog.isVisible = true

               },::error)

           }

        }

    }

    private fun updateDevicesList() {
        adbController.connectedDevices({ devices->
            this.devices = devices
            selectedIDevice = this.devices.getOrElse(devices.indexOf(selectedIDevice)){this.devices.getOrNull(0)}
            devicesListComboBox.model = DefaultComboBoxModel<String>(
                devices.map { device ->
                    device.name
                }.toTypedArray()
            )
        },::showError)
    }

    private fun showError(message:String){
        val noti = NotificationGroup("Spock ADB", NotificationDisplayType.BALLOON, true)
        noti.createNotification("Spock ADB",
            message,
            NotificationType.ERROR,
            null
        ).notify(null)

    }
    private fun showSuccess(message: String){
        val nomi =   NotificationGroup("Spock ADB", NotificationDisplayType.BALLOON, true)
        nomi.createNotification("Spock ADB",
            message,
            NotificationType.INFORMATION,
            null
        ).notify(null)
    }

}










