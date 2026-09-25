package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * The selected node: a selector to find it by, then its properties.
 *
 * A selector comes first because it is what a developer came for — something to paste into an
 * MCP call or a test — and it is checked against the captured screen, so one that would find
 * three nodes says so before it is pasted anywhere.
 */
internal class NodeDetailsPanel(private val onNotice: (String) -> Unit) : JPanel(BorderLayout()) {

    private val model = PropertiesModel()
    private val table = JBTable(model).apply {
        setShowGrid(false)
        tableHeader = null
        emptyText.text = "Select an element to see its properties"
        setDefaultRenderer(Any::class.java, PropertyRenderer())
        columnModel.getColumn(0).preferredWidth = JBUI.scale(NAME_COLUMN_WIDTH)
        columnModel.getColumn(0).maxWidth = JBUI.scale(NAME_COLUMN_WIDTH * 2)
    }

    private val selectorValue = JBLabel().apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
    }
    private val selectorCheck = JBLabel()
    private val copyMcp = copyLink("Copy MCP selector") { it.mcpJson }
    private val copyCompose = copyLink("Copy Compose test") { it.composeTest }
    private val copyUiAutomator = copyLink("Copy UI Automator") { it.uiAutomator }
    private val selectorHeading = TitledSeparator("Selector")
    private val selectorPanel = JPanel()

    private val hint = InspectorNote(AllIcons.General.Information).apply {
        text = NodeProperties.NOT_INTERACTIVE_HINT
        border = JBUI.Borders.empty(GAP, GAP * 2)
    }

    private var suggestion: SelectorSuggestion? = null

    init {
        selectorPanel.layout = BoxLayout(selectorPanel, BoxLayout.Y_AXIS)
        selectorPanel.border = JBUI.Borders.empty(0, GAP * 2, GAP, GAP * 2)
        selectorPanel.add(selectorHeading)
        listOf(
            selectorValue,
            selectorCheck,
            JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(LINK_GAP), JBUI.scale(2))).apply {
                add(copyMcp)
                add(copyCompose)
                add(copyUiAutomator)
            },
        ).forEach {
            it.alignmentX = Component.LEFT_ALIGNMENT
            selectorPanel.add(it)
        }

        add(
            JPanel(BorderLayout()).apply {
                add(selectorPanel, BorderLayout.NORTH)
                add(hint, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table), BorderLayout.CENTER)
        show(null, null, null)
    }

    /**
     * [node] from [observation]'s tree, or nothing selected. [tree] is where selectors are checked;
     * [visibility] is where [node] is relative to the capture's viewport.
     */
    fun show(node: UiNode?, observation: UiObservation?, tree: UiTree?, visibility: NodeVisibility? = null) {
        model.show(node?.let { NodeProperties.of(it, observation?.densityDpi, visibility) }.orEmpty())
        hint.isVisible = node != null && !node.isInteractive

        val framework = tree?.framework ?: UiFramework.UNKNOWN
        suggestion = node?.let { SelectorSuggestion.forNode(it, framework) }
        selectorPanel.isVisible = node != null
        showSelector(node, tree)
    }

    private fun showSelector(node: UiNode?, tree: UiTree?) {
        val current = suggestion
        copyMcp.isEnabled = current != null
        copyCompose.isEnabled = current?.composeTest != null
        copyUiAutomator.isEnabled = current != null
        if (node == null || tree == null) return
        if (current == null) {
            selectorHeading.text = "Selector"
            selectorValue.text = "No selector"
            selectorCheck.icon = AllIcons.General.Warning
            selectorCheck.text = "This element has no test tag, text or content description to find it by."
            selectorCheck.toolTipText = null
            selectorValue.toolTipText = null
            return
        }
        // On a Views screen the "tag" is a View id, and calling it a test tag would mislead.
        val basis = when {
            current.basis == SelectorSuggestion.Basis.TEST_TAG && tree.framework == UiFramework.VIEWS -> "resource id"
            else -> current.basis.label
        }
        selectorHeading.text = "Selector · by $basis"
        selectorValue.text = current.mcpJson
        selectorValue.toolTipText = current.mcpJson

        val check = current.check(tree, node)
        selectorCheck.icon = if (check.isUnique) AllIcons.General.InspectionsOK else AllIcons.General.Warning
        selectorCheck.text = check.describe()
        // The refusal an element action would give: which candidates, and what tells them apart.
        selectorCheck.toolTipText = check.ambiguity?.let { "<html>${StringUtil.escapeXmlEntities(it)}</html>" }
    }

    private fun copyLink(text: String, value: (SelectorSuggestion) -> String?): ActionLink =
        ActionLink(
            text,
            ActionListener {
                suggestion?.let(value)?.let {
                    CopyPasteManager.getInstance().setContents(StringSelection(it))
                    onNotice("Copied $it")
                }
            },
        )

    /** Section headings and their properties, as two columns. Read-only; Ctrl+C copies rows. */
    private class PropertiesModel : AbstractTableModel() {
        private var rows: List<Any> = emptyList()

        fun show(sections: List<PropertySection>) {
            rows = sections.flatMap { listOf<Any>(it.title) + it.rows }
            fireTableDataChanged()
        }

        fun row(index: Int): Any = rows[index]

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (val row = rows[rowIndex]) {
            is PropertySection.Property -> if (columnIndex == 0) row.name else row.value
            else -> if (columnIndex == 0) row.toString() else ""
        }
    }

    private class PropertyRenderer : ColoredTableCellRenderer() {
        @Suppress("LongParameterList")
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            val heading = (table.model as PropertiesModel).row(row) !is PropertySection.Property
            val attributes = when {
                heading -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                column == 0 -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            // Device-supplied values, appended as a fragment so they are never read as markup.
            append(value?.toString().orEmpty(), attributes)
        }
    }

    private companion object {
        const val GAP = 4
        const val LINK_GAP = 12
        const val NAME_COLUMN_WIDTH = 140
    }
}
