package spock.adb.premission

import spock.adb.AdbController
import java.awt.Component
import java.awt.event.*
import javax.swing.*


class PermissionDialog(val adbPremission: AdbController) : JDialog() {
    private lateinit var contentPane: JPanel
    private lateinit var topPane: JPanel
    private lateinit var buttonOK: JButton
    private lateinit var buttonCancel: JButton
    private lateinit var jList: JList<PermissionListItem>

    init {
        setContentPane(contentPane)
        isModal = true
        getRootPane().defaultButton = buttonOK
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
        jList.model = listModel
        jList.cellRenderer = CheckListRenderer()
        jList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        jList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                handelPermissionSelection(event)
            }
        })
    //    adbPremission.getApplicationPermissions()
    }

    private fun handelPermissionSelection(event: MouseEvent) {
        val list = event.source as JList<*>
        val index = list.locationToIndex(event.point)// Get index of item
        // clicked
        val item = list.model
            .getElementAt(index) as PermissionListItem
        item.isSelected = !item.isSelected // Toggle selected state
        list.repaint(list.getCellBounds(index, index))// Repaint cell
    }

    private fun onCancel() {
        dispose()
    }


    internal class CheckListRenderer : JCheckBox(), ListCellRenderer<Any> {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any,
            index: Int, isSelected: Boolean, hasFocus: Boolean
        ): Component {
            isEnabled = list.isEnabled
            setSelected((value as PermissionListItem).isSelected)
            font = list.font
            background = list.background
            foreground = list.foreground
            text = value.toString()
            return this
        }
    }
}
