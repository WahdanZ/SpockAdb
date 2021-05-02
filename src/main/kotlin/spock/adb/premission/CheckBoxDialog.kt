package spock.adb.premission

import java.awt.Component
import java.awt.Dimension
import java.awt.event.*
import javax.swing.*

class CheckBoxDialog(
    private val list: List<ListItem>,
    private val onItemCheck: (item: ListItem) -> Unit,
) : JDialog() {
    private lateinit var contentPane: JPanel
    private lateinit var jList: JList<ListItem>

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
        val listModel = DefaultListModel<ListItem>()
        list.forEach {
            listModel.addElement(it)
        }
        jList.model = listModel
        jList.cellRenderer = CheckListRenderer()
        jList.size = Dimension(24, 24)
        jList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        jList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                handelSelection(event)
            }
        })
    //    adaPermission.getApplicationPermissions()
    }

    private fun handelSelection(event: MouseEvent) {
        val list = event.source as JList<*>
        val index = list.locationToIndex(event.point) // Get index of item
        // clicked
        val item = list.model
            .getElementAt(index) as ListItem
        item.isSelected = !item.isSelected // Toggle selected state
        onItemCheck(item)

        list.repaint(list.getCellBounds(index, index)) // Repaint cell
    }

    private fun onCancel() {
        dispose()
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
            setSelected((value as ListItem).isSelected)
            font = list.font
            background = list.background
            foreground = list.foreground
            text = value.name
            return this
        }
    }
}
