package spock.adb.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ToolTipManager

/**
 * The Activity Stack popup's list.
 *
 * A list rather than a chooser over plain strings, because the rows are two levels deep — a task
 * and the activities under it — and the popup needs to say which task is in front and which
 * rows can actually be opened. Tooltips are answered here so a truncated row in a narrow tool
 * window still gives up its full package or class name.
 */
internal class ActivityStackList(rows: List<ActivityStackRow>) : JBList<ActivityStackRow>(rows) {

    init {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ActivityStackRenderer()
        // JList answers tooltips from the renderer, but only once it is registered.
        ToolTipManager.sharedInstance().registerComponent(this)
        selectFirstOpenableRow()
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val index = locationToIndex(event.point)
        if (index < 0) return null
        if (getCellBounds(index, index)?.contains(event.point) != true) return null
        return model.getElementAt(index).tooltip()
    }

    /**
     * Opens on an activity rather than on the task heading above it, so Enter on a freshly
     * opened popup does something.
     */
    private fun selectFirstOpenableRow() {
        val index = (0 until model.size).firstOrNull { model.getElementAt(it).className() != null }
        if (index != null) selectedIndex = index
    }
}

/**
 * Draws a task heading, an activity under it, or the named gap where an activity could not be
 * read. Indentation and weight carry the hierarchy; the `CURRENT` badge carries the foreground
 * task, so it survives the selection moving somewhere else.
 *
 * A task whose app name is known reads as that name over its package, which is the pair a
 * developer recognises an app by — the name alone is ambiguous across build variants, and the
 * package alone is what made the old popup hard to scan.
 */
internal class ActivityStackRenderer : ListCellRenderer<ActivityStackRow> {

    private val text = JBLabel()
    private val secondary = JBLabel().apply { font = JBFont.small() }

    // BorderLayout rather than a BoxLayout column: one renderer component is reused for every
    // cell, and BoxLayout caches the child sizes it measured for the row before — which draws
    // a long package name ellipsised to the width of the short one above it. BorderLayout
    // measures each time, and skips the second line outright while it is hidden.
    private val lines = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(text, BorderLayout.NORTH)
        add(secondary, BorderLayout.CENTER)
    }
    private val badge = JBLabel(CURRENT_BADGE).apply { font = JBFont.small().asBold() }

    // Top-aligned rather than centred, so the badge sits against the app name of a two-line task
    // row rather than floating between its two lines.
    private val badgeColumn = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(badge, BorderLayout.NORTH)
    }

    private val panel = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
        isOpaque = true
        add(lines, BorderLayout.CENTER)
        add(badgeColumn, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out ActivityStackRow>,
        value: ActivityStackRow?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val row = value ?: return panel

        panel.background = if (isSelected) list.selectionBackground else list.background
        panel.border = when (row) {
            is ActivityStackRow.Task -> JBUI.Borders.empty(TASK_TOP_PAD, PAD, ROW_PAD, PAD)
            else -> JBUI.Borders.empty(ROW_PAD, INDENT, ROW_PAD, PAD)
        }

        text.text = row.displayText()
        text.font = if (row is ActivityStackRow.Task) JBFont.label().asBold() else JBFont.label()
        text.foreground = when {
            isSelected -> list.selectionForeground
            row is ActivityStackRow.NoActivity -> UIUtil.getContextHelpForeground()
            else -> list.foreground
        }
        // Set on the renderer too: JList forwards tooltip questions here for cells it draws.
        text.toolTipText = row.tooltip()

        val second = row.secondaryText()
        secondary.isVisible = second != null
        secondary.text = second.orEmpty()
        secondary.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()

        badge.isVisible = row is ActivityStackRow.Task && row.isForeground
        badge.foreground = if (isSelected) list.selectionForeground else CURRENT_COLOUR

        return panel
    }

    private companion object {
        const val GAP = 8
        const val PAD = 10
        const val INDENT = 26
        const val ROW_PAD = 2
        const val TASK_TOP_PAD = 8
        val CURRENT_COLOUR = JBColor(0x1A7F37, 0x57A64A)
    }
}
