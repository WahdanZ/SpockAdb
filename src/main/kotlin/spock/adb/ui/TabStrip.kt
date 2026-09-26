package spock.adb.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JToggleButton

/**
 * A row of tabs that shows as many as fit and puts the rest behind **More**.
 *
 * Swing's own tabbed pane cannot do this. `WRAP_TAB_LAYOUT` stacks seven tabs into four rows in
 * a tool window docked at 300px, taking the height the window was docked for; and
 * `SCROLL_TAB_LAYOUT` cannot be used at all, because IntelliJ's `DarculaTabbedPaneUI` recurses
 * between `ensureSelectedTabIsVisible` and `tabForCoordinate` until the stack overflows.
 *
 * Tabs keep the order they were added in, so a tab is always found where it was last time. The
 * selected tab is never the one hidden: when it would overflow, it takes the last place on the
 * row instead, and only the tab it displaces moves into More.
 */
internal class TabStrip : JPanel(null) {

    private val cards = CardLayout()

    /** The tab contents, switched by [cards]. */
    val content: JPanel = JPanel(cards)

    private val group = ButtonGroup()
    private val buttons = linkedMapOf<String, JToggleButton>()

    private val more = JButton(MORE_LABEL).apply {
        toolTipText = "The tabs that do not fit"
        isVisible = false
        addActionListener { showOverflow() }
    }

    /** Called with the title of the tab that became visible. */
    var onSelected: (String) -> Unit = {}

    private var selected: String? = null

    init {
        border = JBUI.Borders.empty(0, GAP, 2, GAP)
        add(more)
    }

    fun addTab(title: String, tab: JComponent) {
        val button = TabButton(title).apply { addActionListener { select(title) } }
        buttons[title] = button
        group.add(button)
        add(button)
        content.add(tab, title)
        if (selected == null) select(title)
    }

    /** The tab showing now. */
    val selectedTitle: String? get() = selected

    fun select(title: String) {
        if (title !in buttons) return
        selected = title
        buttons.forEach { (name, button) -> button.isSelected = name == title }
        cards.show(content, title)
        revalidate()
        repaint()
        onSelected(title)
    }

    /**
     * Places the tabs left to right, reserving room for More as soon as one will not fit.
     *
     * Two passes rather than one: whether More is needed decides how much room the tabs have,
     * and that in turn decides whether More is needed.
     */
    override fun doLayout() {
        val insets = insets
        val top = insets.top
        val rowHeight = height - insets.top - insets.bottom
        val gap = JBUI.scale(GAP)
        val titles = buttons.keys.toList()
        val widths = titles.map { buttons.getValue(it).preferredSize.width }
        val full = insets.left + widths.sumOf { it + gap }
        val moreWidth = more.preferredSize.width + gap
        val limit = width - insets.right - if (full > width - insets.right) moreWidth else 0

        val shown = tabRun(widths, titles.indexOf(selected), limit - insets.left, gap)
        titles.forEachIndexed { index, title -> buttons.getValue(title).isVisible = index in shown }
        var x = insets.left
        shown.forEach { index ->
            val button = buttons.getValue(titles[index])
            button.setBounds(x, top, widths[index], rowHeight)
            x += widths[index] + gap
        }
        overflow = titles.filterIndexed { index, _ -> index !in shown }
        more.isVisible = overflow.isNotEmpty()
        if (more.isVisible) {
            more.setBounds(width - insets.right - more.preferredSize.width, top, more.preferredSize.width, rowHeight)
        }
    }

    override fun getPreferredSize(): Dimension {
        val insets = insets
        val tallest = buttons.values.maxOfOrNull { it.preferredSize.height } ?: more.preferredSize.height
        return Dimension(0, tallest + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    private var overflow: List<String> = emptyList()

    private fun showOverflow() {
        JPopupMenu().apply {
            overflow.forEach { title ->
                add(JMenuItem(title).apply { addActionListener { select(title) } })
            }
        }.show(more, 0, more.height)
    }

    /**
     * One tab, drawn as a tab rather than as a button.
     *
     * A `JToggleButton` in the IDE's own look is all but indistinguishable selected from not —
     * a faintly different shade of the same button — so which tab you were on had to be
     * inferred from what was below it. This paints the two states apart: the selected tab takes
     * the accent colour and an underline, the rest stay quiet until the pointer is over them.
     */
    private class TabButton(title: String) : JToggleButton(title) {
        init {
            toolTipText = title
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            isRolloverEnabled = true
            border = JBUI.Borders.empty(PAD_V, PAD_H)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Quiet until it is the one being pointed at: a background on every tab would
                // put seven boxes where there is one row.
                if (model.isRollover && !isSelected) {
                    g2.color = HOVER
                    g2.fillRoundRect(0, 0, width, height - underline(), JBUI.scale(ARC), JBUI.scale(ARC))
                }
                foreground = if (isSelected) ACCENT else UIUtil.getInactiveTextColor()
                super.paintComponent(g2)
                if (isSelected) {
                    g2.color = ACCENT
                    g2.fillRect(0, height - underline(), width, underline())
                }
            } finally {
                g2.dispose()
            }
        }

        private fun underline() = JBUI.scale(UNDERLINE)

        private companion object {
            const val PAD_V = 4
            const val PAD_H = 10
            const val ARC = 6
            const val UNDERLINE = 2

            /** The IDE's own accent where the theme names one, so the row belongs to the theme. */
            val ACCENT = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x548AF7))
            val HOVER = JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xEDEDED, 0x3E4245))
        }
    }

    private companion object {
        const val GAP = 4
        const val MORE_LABEL = "More ▾"
    }
}

/**
 * Which tabs are on the row, as indexes in the order they are drawn.
 *
 * Tabs are placed in their own order until one does not fit in [room]. If [selected] is among
 * those left over, tabs are taken off the end of the run until it fits, and it goes last: every
 * tab before it keeps its place, and the selected one is still on screen.
 *
 * @param widths each tab's width, in the order the tabs were added.
 * @param selected the selected tab's index, or -1 when none is.
 */
internal fun tabRun(widths: List<Int>, selected: Int, room: Int, gap: Int): List<Int> {
    val run = mutableListOf<Int>()
    var used = 0
    for (index in widths.indices) {
        if (used + widths[index] > room) break
        run += index
        used += widths[index] + gap
    }
    if (selected !in widths.indices || selected in run) return run
    while (run.isNotEmpty() && used + widths[selected] > room) {
        used -= widths[run.removeAt(run.lastIndex)] + gap
    }
    return run + selected
}
