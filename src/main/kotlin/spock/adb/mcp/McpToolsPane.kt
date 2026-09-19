package spock.adb.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The catalogue of what an agent can actually do to the device, grouped by how much it is
 * allowed to do.
 *
 * The panel used to report only a count, which told a developer nothing about what they were
 * exposing when they pressed Start. Fifty bare identifiers told them little more: each row now
 * carries the one-line description as well as the name, and the heading above it says whether
 * the tools under it run on their own or have to be approved first.
 */
internal class McpToolsPane : JPanel(BorderLayout()) {

    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val search = JBTextField(SEARCH_COLUMNS)
    private val destructiveOnly = JBCheckBox("Destructive only")

    /** Called on the EDT with the selected tool's details, ready to show, or null for none. */
    var onSelected: (String?) -> Unit = {}

    init {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        list.cellRenderer = ToolRenderer()
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onSelected((list.selectedValue as? Row.Entry)?.tool?.let(::describe))
        }
        search.addKeyListener(
            object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) = refresh()
            },
        )
        destructiveOnly.addActionListener { refresh() }

        add(
            JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
                border = JBUI.Borders.empty(2, GAP)
                add(JBLabel("Search:"))
                add(search)
                add(destructiveOnly)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(list), BorderLayout.CENTER)
        refresh()
    }

    private fun refresh() {
        val query = search.text.orEmpty()
        model.clear()
        // Destructive first: the tools a developer most needs to know about should not be
        // buried at the bottom of an alphabetical list.
        ToolSafety.entries.sortedByDescending { it.ordinal }.forEach { safety ->
            if (destructiveOnly.isSelected && safety != ToolSafety.DESTRUCTIVE) return@forEach

            val matching = ToolRegistry.bySafety(safety)
                .filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
                .sortedBy { it.name }
            if (matching.isEmpty()) return@forEach

            model.addElement(Row.Header(safety, matching.size))
            matching.forEach { model.addElement(Row.Entry(it)) }
        }
    }

    private fun describe(tool: AdbTool): String = buildString {
        appendLine(tool.name)
        appendLine()
        appendLine("Access: ${tool.safety.describe()}")
        if (tool.safety == ToolSafety.DESTRUCTIVE) {
            appendLine("        You are asked to approve every call, and denial is the default.")
        }
        appendLine()
        appendLine("Description:")
        appendLine(tool.description)
        appendLine()
        appendLine("Arguments:")
        append(prettyJson(tool.inputSchema))
    }

    private fun prettyJson(json: JsonObject): String =
        runCatching { GsonBuilder().setPrettyPrinting().create().toJson(json) }.getOrDefault(json.toString())

    /** A row is either a safety heading or a tool, so one list renders the grouping. */
    private sealed interface Row {
        data class Header(val safety: ToolSafety, val count: Int) : Row
        data class Entry(val tool: AdbTool) : Row
    }

    private class ToolRenderer : ColoredListCellRenderer<Row>() {
        override fun customizeCellRenderer(
            list: JList<out Row>,
            value: Row?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            border = JBUI.Borders.empty(ROW_PAD_V, ROW_PAD_H)
            when (value) {
                is Row.Header -> {
                    append(
                        "${value.safety.marker()}  ${value.safety.heading()} (${value.count})",
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, value.safety.colour()),
                    )
                }
                is Row.Entry -> {
                    val name = SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        if (value.tool.safety == ToolSafety.DESTRUCTIVE) DESTRUCTIVE else JBColor.foreground(),
                    )
                    append(TOOL_INDENT)
                    append(value.tool.name, name)
                    // The name says what it touches; the description says what it does with it.
                    append("  ${value.tool.description.summary()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = value.tool.description
                }
                null -> Unit
            }
        }
    }

    private companion object {
        const val GAP = 4
        const val SEARCH_COLUMNS = 14
        const val ROW_PAD_V = 1
        const val ROW_PAD_H = 6
        const val TOOL_INDENT = "      "
        const val SUMMARY_LIMIT = 72

        val RUNNING = JBColor(0x1F6F4A, 0x57BA8C)
        val DESTRUCTIVE = JBColor(0x8A6100, 0xE0A030)
        val ACTION = JBColor(0x2C5D92, 0x6EA8E0)

        /** The first sentence, and not more of it than a docked tool window can show. */
        fun String.summary(): String {
            val sentence = substringBefore(". ").trim().removeSuffix(".")
            return if (sentence.length <= SUMMARY_LIMIT) sentence else sentence.take(SUMMARY_LIMIT).trim() + "…"
        }

        fun ToolSafety.marker(): String = when (this) {
            ToolSafety.READ_ONLY -> "✓"
            ToolSafety.SAFE_ACTION -> "⚡"
            ToolSafety.DESTRUCTIVE -> "⚠"
        }

        fun ToolSafety.heading(): String = when (this) {
            ToolSafety.READ_ONLY -> "Read-only — run automatically"
            ToolSafety.SAFE_ACTION -> "Actions — run automatically"
            ToolSafety.DESTRUCTIVE -> "Destructive — always ask first"
        }

        fun ToolSafety.colour(): JBColor = when (this) {
            ToolSafety.READ_ONLY -> RUNNING
            ToolSafety.SAFE_ACTION -> ACTION
            ToolSafety.DESTRUCTIVE -> DESTRUCTIVE
        }

        fun ToolSafety.describe(): String = when (this) {
            ToolSafety.READ_ONLY -> "✓ read-only"
            ToolSafety.SAFE_ACTION -> "⚡ action"
            ToolSafety.DESTRUCTIVE -> "⚠ destructive — requires confirmation"
        }
    }
}
