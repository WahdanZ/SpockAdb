package spock.adb.premission

import java.awt.Component
import java.awt.Dimension
import java.awt.event.*
import javax.swing.*

/**
 * A list of names with a checkbox each; a left click toggles one and reports it.
 *
 * @param label the text shown for a row, which can say more than its name.
 * @param rowMenu the actions offered when a row is right-clicked; a row with none shows no menu.
 */
class CheckBoxDialog(
    private val list: List<ListItem>,
    private val label: (ListItem) -> String = { it.name },
    private val rowMenu: ((ListItem) -> List<Pair<String, () -> Unit>>)? = null,
    private val onItemCheck: (item: ListItem) -> Unit,
) : JDialog() {
    private lateinit var contentPane: JPanel
    private lateinit var jList: JList<ListItem>

    /** Set by a right-click, so the click event that follows it does not also toggle the row. */
    private var contextClick = false

    init {
        javaClass.getDeclaredMethod("\$\$\$setupUI\$\$\$").invoke(this)
        setContentPane(contentPane)
        isModal = true
        prepareList()
        defaultCloseOperation = DISPOSE_ON_CLOSE
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
        jList.cellRenderer = CheckListRenderer(label)
        jList.size = Dimension(24, 24)
        jList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        jList.addMouseListener(object : MouseAdapter() {
            // The popup trigger is the press on macOS and Linux, the release on Windows.
            override fun mousePressed(event: MouseEvent) {
                contextClick = false
                showRowMenu(event)
            }

            override fun mouseReleased(event: MouseEvent) {
                showRowMenu(event)
            }

            override fun mouseClicked(event: MouseEvent) {
                if (contextClick) {
                    contextClick = false
                } else if (SwingUtilities.isLeftMouseButton(event)) {
                    handelSelection(event)
                }
            }
        })
    }

    /**
     * The row under the pointer, or -1 below the last one: [JList.locationToIndex] answers with
     * the nearest row, so a click in the empty space under the list used to toggle the last one.
     */
    private fun rowAt(event: MouseEvent): Int {
        val index = jList.locationToIndex(event.point)
        return if (index >= 0 && jList.getCellBounds(index, index)?.contains(event.point) == true) index else -1
    }

    private fun showRowMenu(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        contextClick = true
        val index = rowAt(event)
        if (index < 0) return
        val actions = rowMenu?.invoke(jList.model.getElementAt(index)).orEmpty()
        if (actions.isEmpty()) return
        val menu = JPopupMenu()
        actions.forEach { (text, action) -> menu.add(JMenuItem(text).apply { addActionListener { action() } }) }
        menu.show(jList, event.x, event.y)
    }

    private fun handelSelection(event: MouseEvent) {
        val index = rowAt(event)
        if (index < 0) return
        val item = jList.model.getElementAt(index)
        item.isSelected = !item.isSelected // Toggle selected state
        onItemCheck(item)

        jList.repaint(jList.getCellBounds(index, index)) // Repaint cell
    }

    /** Paints every row again, for a caller whose [label] has changed its answer. */
    fun refreshRows() = jList.repaint()

    private fun onCancel() {
        dispose()
    }



    class CheckListRenderer(private val label: (ListItem) -> String) : JCheckBox(), ListCellRenderer<Any> {
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
            text = label(value)
            return this
        }
    }
}
