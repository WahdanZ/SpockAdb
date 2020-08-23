package spock.adb.premission

import com.android.ddmlib.IDevice
import java.awt.Component
import java.awt.event.*
import javax.swing.*
import spock.adb.AdbController

class PermissionDialog(
    private val device: IDevice,
    private val adaPermission: AdbController,
    private val permissionList: List<PermissionListItem>
) : JDialog() {
    private lateinit var contentPane: JPanel
    private lateinit var jList: JList<PermissionListItem>

    init {
        setContentPane(contentPane)
        isModal = true
        prepareList()
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                onCancel()
            }
        })
        contentPane.registerKeyboardAction(
            { onCancel() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
    }

    private fun prepareList() {
        val listModel = DefaultListModel<PermissionListItem>()
        permissionList.forEach {
            listModel.addElement(it)
        }
        jList.model = listModel
        jList.cellRenderer = CheckListRenderer()
        jList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        jList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                handelPermissionSelection(event)
            }
        })
    //    adaPermission.getApplicationPermissions()
    }

    private fun handelPermissionSelection(event: MouseEvent) {
        val list = event.source as JList<*>
        val index = list.locationToIndex(event.point) // Get index of item
        // clicked
        val item = list.model
            .getElementAt(index) as PermissionListItem
        item.isSelected = !item.isSelected // Toggle selected state
        handelPermissionSelection(device, item)

        list.repaint(list.getCellBounds(index, index)) // Repaint cell
    }

    private fun onCancel() {
        dispose()
    }

    private fun handelPermissionSelection(device: IDevice, permissionListItem: PermissionListItem) {
        if (permissionListItem.isSelected) {
            adaPermission.grantPermission(device, permissionListItem, ::print, ::error)
        } else {
            adaPermission.revokePermission(device, permissionListItem, ::print, ::error)
        }
    }

    class CheckListRenderer : JCheckBox(), ListCellRenderer<Any> {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any,
            index: Int,
            isSelected: Boolean,
            hasFocus: Boolean
        ): Component {
            isEnabled = list.isEnabled
            setSelected((value as PermissionListItem).isSelected)
            font = list.font
            background = list.background
            foreground = list.foreground
            text = value.permission
            return this
        }
    }
}
