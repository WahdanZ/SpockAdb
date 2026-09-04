package spock.adb.ui

import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * A panel that scrolls vertically only, and always matches the viewport's width.
 *
 * Inside a plain `JScrollPane` a panel is laid out at its *preferred* width, so a two-column
 * grid of buttons sizes itself from the widest label and the scroll pane grows a horizontal
 * scrollbar — which is exactly what clipped the right-hand column of the Devices tab. A tool
 * window is often docked at 300px, so content must shrink to the width available rather than
 * demand its own.
 *
 * `getScrollableTracksViewportWidth() = true` is what forces the layout to re-run at the
 * viewport's width instead.
 */
class VerticallyScrollablePanel(layout: LayoutManager? = null) : JPanel(layout), Scrollable {

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableTracksViewportWidth(): Boolean = true

    /** False, so the panel keeps its natural height and the pane scrolls vertically. */
    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = UNIT_INCREMENT

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = when (orientation) {
        SwingConstants.VERTICAL -> visibleRect.height
        else -> visibleRect.width
    }

    private companion object {
        const val UNIT_INCREMENT = 16
    }
}
