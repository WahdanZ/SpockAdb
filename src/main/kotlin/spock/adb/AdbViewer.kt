package spock.adb
import com.android.ddmlib.IDevice
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import javax.swing.*
import com.intellij.testFramework.LightPlatformTestCase.getProject
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep


class AdbViewer( private val  adbController: AdbController
) : SimpleToolWindowPanel(true) {
    private lateinit var panel1: JPanel
    private lateinit var rootPanel: JPanel
    private lateinit var devicesListComboBox: JComboBox<String>
    private lateinit var currentActivityButton: JButton
    private lateinit var currentFragmentButton: JButton
    private lateinit var clearAppDataButton: JButton
    private lateinit var revokePremissionButton: JButton
    private lateinit var grantPremissionButton: JButton
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
                adbController.currentActivity(device,::showError)
            }
        }
        currentFragmentButton.addActionListener {
            selectedIDevice?.let {device->
                adbController.currentFragment(device,::showError)
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
        revokePremissionButton.addActionListener {


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
        Messages.showErrorDialog("Spock ADB",message)

    }
    private fun showSuccess(message: String){
        Messages.showInfoMessage("Spock ADB", message)

    }
    fun getPopupStep(): ListPopup {
        val connectionGroup = DefaultActionGroup()

      return  PopupFactoryImpl.getInstance().createActionGroupPopup(
            "Hll",
            connectionGroup,
            SimpleDataContext.getProjectContext(getProject()),
            JBPopupFactory.ActionSelectionAid.NUMBERING,
            true)
    }

}










