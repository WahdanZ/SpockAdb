package spock.adb.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * Fills the tool window when there is room, and scrolls rather than squeezes when there is not.
 *
 * A panel of a header, a tree and a details pane shares out whatever height it is given. Docked
 * in a short slot — Spock Screen stacked above Spock ADB, or a laptop screen — each part got a
 * sliver: the tree two rows, the details cut off, header text over the controls below it. Below
 * [minHeight] the content is laid out at that height instead, and the window scrolls to it.
 *
 * Only the height has a floor. The width always follows the window, so nothing ever scrolls
 * sideways, and the content's own scroll panes (the tree, the tables) keep scrolling as before.
 */
class MinHeightScrollPane(content: JComponent, private val minHeight: Int) : JBScrollPane() {

    init {
        setViewportView(Holder(content))
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    private inner class Holder(content: JComponent) : JPanel(BorderLayout()), Scrollable {

        init {
            add(content, BorderLayout.CENTER)
        }

        private val floor: Int get() = JBUI.scale(minHeight)

        /**
         * Exactly the floor, not the content's own preferred height: a tree's preferred height is
         * every row it holds, and asking for that would scroll the whole panel instead of the tree.
         */
        override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, floor)

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean =
            ((parent as? JViewport)?.height ?: 0) >= floor

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(UNIT_INCREMENT)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
    }

    private companion object {
        const val UNIT_INCREMENT = 16
    }
}
