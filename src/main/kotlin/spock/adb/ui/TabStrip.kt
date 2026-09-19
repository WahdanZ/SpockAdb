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
 * The selected tab is never the one hidden: it is moved to the front of the visible run, so the
 * tab you are on is always on screen.
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
        val order = visibleOrder()
        val moreWidth = more.preferredSize.width + JBUI.scale(GAP)
        val full = insets.left + order.sumOf { buttons.getValue(it).preferredSize.width + JBUI.scale(GAP) }
        val limit = width - insets.right - if (full > width - insets.right) moreWidth else 0

        var x = insets.left
        val hidden = mutableListOf<String>()
        order.forEach { title ->
            val button = buttons.getValue(title)
            val w = button.preferredSize.width
            if (x + w <= limit) {
                button.isVisible = true
                button.setBounds(x, top, w, rowHeight)
                x += w + JBUI.scale(GAP)
            } else {
                button.isVisible = false
                hidden += title
            }
        }
        overflow = hidden
        more.isVisible = hidden.isNotEmpty()
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

    /** The selected tab first, so the one being looked at is never the one pushed into More. */
    private fun visibleOrder(): List<String> {
        val titles = buttons.keys.toList()
        val current = selected ?: return titles
        return listOf(current) + titles.filterNot { it == current }
    }

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
