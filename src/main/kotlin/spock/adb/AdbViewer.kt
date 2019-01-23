package spock.adb
import com.android.ddmlib.IDevice
import com.intellij.openapi.ui.SimpleToolWindowPanel
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*

class AdbViewer(
) : SimpleToolWindowPanel(true), ActionListener {
    override fun actionPerformed(e: ActionEvent?) {
    }

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
        //  updateDevicesList()

        devicesListComboBox.addItemListener {
            selectedIDevice = devices[devicesListComboBox.selectedIndex]
        }

    }
}





