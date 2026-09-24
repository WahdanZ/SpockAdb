package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The accessibility audit's findings as a list, one row each, with the fix for whichever is
 * selected underneath.
 *
 * It was a text dump in the details pane: readable, but a finding could not be taken back to the
 * element it was about. Selecting one here selects that element in the tree.
 */
internal class AuditFindingsPanel(private val onSelectNode: (UiNode) -> Unit) : JPanel(BorderLayout()) {

    private val model = CollectionListModel<AccessibilityAudit.Finding>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = FindingRenderer()
        emptyText.text = NOT_RUN
    }
    private val fix = InspectorNote(AllIcons.Actions.IntentionBulb, grey = false).apply {
        border = JBUI.Borders.empty(GAP, GAP * 2, 0, GAP * 2)
        isVisible = false
    }
    private val coverage = InspectorNote().apply {
        border = JBUI.Borders.empty(GAP, GAP * 2)
    }

    private var framework = UiFramework.UNKNOWN

    init {
        list.addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            val finding = list.selectedValue
            fix.isVisible = finding != null
            if (finding == null) return@addListSelectionListener
            fix.text = if (framework == UiFramework.VIEWS) finding.viewFix else finding.composeFix
            onSelectNode(finding.node)
        }
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                add(fix, BorderLayout.NORTH)
                add(coverage, BorderLayout.SOUTH)
            },
            BorderLayout.SOUTH,
        )
        coverage.isVisible = false
    }

    fun show(findings: List<AccessibilityAudit.Finding>, tree: UiTree) {
        framework = tree.framework
        model.replaceAll(findings)
        list.emptyText.text = "No issues detected by these checks."
        fix.isVisible = false
        coverage.text = AccessibilityAudit.coverageNote(tree)
        coverage.isVisible = true
    }

    /** A new capture: findings about the last one no longer apply. */
    fun clear() {
        model.removeAll()
        list.emptyText.text = NOT_RUN
        fix.isVisible = false
        coverage.isVisible = false
    }

    private class FindingRenderer : ColoredListCellRenderer<AccessibilityAudit.Finding>() {
        override fun customizeCellRenderer(
            list: JList<out AccessibilityAudit.Finding>,
            value: AccessibilityAudit.Finding?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val finding = value ?: return
            icon = when (finding.severity) {
                AccessibilityAudit.Severity.ERROR -> AllIcons.General.Error
                AccessibilityAudit.Severity.WARNING -> AllIcons.General.Warning
            }
            // The issue can quote a label from the device; appended as a fragment, never as markup.
            append(finding.issue)
            val node = finding.node
            val where = listOf(node.className.substringAfterLast('.'), node.label, node.bounds.toString())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            append("  $where", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        const val GAP = 4
        const val NOT_RUN = "Run Accessibility Audit to check this screen."
    }
}
