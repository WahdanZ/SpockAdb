package spock.adb.mcp

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import spock.adb.mcp.tools.ToolSafety
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * What agents have asked the device to do, as a table with headings.
 *
 * It was a single formatted line per call, which put a tick and a cross on the same row — one
 * for how dangerous the tool is, the other for whether the call worked — with nothing saying
 * which was which. They are two different questions, so they are two columns, and the column
 * says what it holds.
 */
internal class McpActivityTable : JPanel(BorderLayout()) {

    private val model = ActivityModel()
    private val table = JBTable(model)

    /** Called on the EDT with the selected call, or null when the selection was cleared. */
    var onSelected: (McpCall?) -> Unit = {}

    val selected: McpCall?
        get() = table.selectedRow.takeIf { it >= 0 }?.let { model.calls.getOrNull(table.convertRowIndexToModel(it)) }

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(false)
        table.setStriped(true)
        table.rowHeight += JBUI.scale(ROW_PADDING)
        table.tableHeader.reorderingAllowed = false
        // Every column gives a little in a narrow tool window, rather than the last one giving
        // everything until it disappears: at 300px the tool name is what has to survive.
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        table.emptyText.text = "No MCP requests yet."
        table.setDefaultRenderer(Any::class.java, CallRenderer())
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onSelected(selected)
        }
        widths()
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    /** Shows [calls], keeping the same call selected when it is still listed. */
    fun show(calls: List<McpCall>) {
        val open = selected
        model.calls = calls
        model.fireTableDataChanged()
        val index = calls.indexOfFirst { it.timestamp == open?.timestamp && it.toolName == open?.toolName }
        if (index >= 0) table.selectionModel.setSelectionInterval(index, index)
    }

    fun clear() = show(emptyList())

    /**
     * Column sizes, as a range rather than a number.
     *
     * The minimum is what keeps every column readable in a tool window docked at 300px; the
     * maximum is what stops Duration taking a fifth of a wide one. Tool has neither beyond a
     * floor: it holds the name, so it should take whatever the others leave.
     */
    private fun widths() {
        listOf(
            TIME to TIME_WIDTH,
            ACCESS to ACCESS_WIDTH,
            RESULT to RESULT_WIDTH,
            DURATION to DURATION_WIDTH,
        ).forEach { (column, width) ->
            table.columnModel.getColumn(column).apply {
                minWidth = JBUI.scale(width * MIN_WIDTH_PERCENT / PERCENT)
                preferredWidth = JBUI.scale(width)
                maxWidth = JBUI.scale(width * MAX_WIDTH_FACTOR)
            }
        }
        table.columnModel.getColumn(TOOL).apply {
            minWidth = JBUI.scale(TOOL_MIN_WIDTH)
            preferredWidth = JBUI.scale(TOOL_WIDTH)
        }
    }

    /** Colours the row by what went wrong, or by how much the tool was allowed to do. */
    private inner class CallRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val call = model.calls.getOrNull(table.convertRowIndexToModel(row))
            horizontalAlignment = if (column == DURATION) SwingConstants.RIGHT else SwingConstants.LEFT
            toolTipText = call?.let { tooltipFor(it, column) }
            if (!isSelected) foreground = colourOf(call, table.foreground)
            return this
        }
    }

    /**
     * The row's colour: what went wrong first, then how much the tool was allowed to do.
     *
     * Declared as returning [Color] because a `when` whose branches are a [JBColor] and the
     * table's own platform-typed foreground infers their common supertype, Serializable.
     */
    private fun colourOf(call: McpCall?, plain: Color): Color = when {
        call == null -> plain
        call.isError -> ERROR
        call.safety == ToolSafety.DESTRUCTIVE -> DESTRUCTIVE
        else -> plain
    }

    private fun tooltipFor(call: McpCall, column: Int): String = when (column) {
        ACCESS -> call.safety.explain()
        RESULT -> if (call.isError) "The tool reported an error; select the row to read it." else "The tool succeeded."
        else -> call.toolName
    }

    private class ActivityModel : AbstractTableModel() {
        var calls: List<McpCall> = emptyList()

        override fun getRowCount(): Int = calls.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getColumnClass(column: Int): Class<*> = String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun getValueAt(row: Int, column: Int): String {
            val call = calls.getOrNull(row) ?: return ""
            return when (column) {
                TIME -> TIME_FORMAT.format(Date(call.timestamp))
                TOOL -> call.toolName
                ACCESS -> call.safety.short()
                RESULT -> if (call.isError) "Error" else "Success"
                else -> "${call.durationMs} ms"
            }
        }
    }

    internal companion object {
        const val TIME = 0
        const val TOOL = 1
        const val ACCESS = 2
        const val RESULT = 3
        const val DURATION = 4

        val COLUMNS = listOf("Time", "Tool", "Access", "Result", "Duration")

        const val TIME_WIDTH = 64
        const val ACCESS_WIDTH = 86
        const val RESULT_WIDTH = 66
        const val DURATION_WIDTH = 74
        const val TOOL_WIDTH = 200
        const val TOOL_MIN_WIDTH = 60
        const val MAX_WIDTH_FACTOR = 2
        const val MIN_WIDTH_PERCENT = 70
        const val PERCENT = 100
        const val ROW_PADDING = 4

        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val ERROR = JBColor(0xB3261E, 0xF2857C)
        val DESTRUCTIVE = JBColor(0x8A6100, 0xE0A030)

        /** One word for the column, where the detail view has room for the sentence. */
        fun ToolSafety.short(): String = when (this) {
            ToolSafety.READ_ONLY -> "Read-only"
            ToolSafety.SAFE_ACTION -> "Action"
            ToolSafety.DESTRUCTIVE -> "Destructive"
        }

        fun ToolSafety.explain(): String = when (this) {
            ToolSafety.READ_ONLY -> "Reads the device and changes nothing; runs without asking."
            ToolSafety.SAFE_ACTION -> "Changes the device in a way that can be undone; runs without asking."
            ToolSafety.DESTRUCTIVE -> "Destroys something; you are asked to approve every call."
        }
    }
}
