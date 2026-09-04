package spock.adb.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that reports a preferred height accounting for wrapping.
 *
 * Plain `FlowLayout` always reports a single row's height, so inside a `BorderLayout` its
 * controls are clipped rather than wrapped — which is exactly why the logcat filter row was
 * sliced in half in a narrow tool window. A tool window is often docked at 300px, so
 * anything that cannot reflow is unusable there.
 */
class WrapLayout(
    align: Int = LEFT,
    hgap: Int = DEFAULT_GAP,
    vgap: Int = DEFAULT_GAP,
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = availableWidth(target)
            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsets

            val dimension = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (!component.isVisible) continue

                val size = if (preferred) component.preferredSize else component.minimumSize

                // Start a new row once this component would overflow the available width.
                if (rowWidth + size.width > maxWidth && rowWidth > 0) {
                    addRow(dimension, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap

                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }
            addRow(dimension, rowWidth, rowHeight)

            dimension.width += horizontalInsets
            dimension.height += insets.top + insets.bottom + vgap * 2

            // Inside a scroll pane the viewport reports the target's own width, which would
            // otherwise feed back and prevent wrapping from ever settling.
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
            if (scrollPane != null && target.isValid) dimension.width -= hgap + 1

            return dimension
        }
    }

    /** Falls back to the parent's width before this container has been sized. */
    private fun availableWidth(target: Container): Int = when {
        target.width > 0 -> target.width
        target.parent != null && target.parent.width > 0 -> target.parent.width
        else -> Int.MAX_VALUE
    }

    private fun addRow(dimension: Dimension, rowWidth: Int, rowHeight: Int) {
        dimension.width = maxOf(dimension.width, rowWidth)
        if (dimension.height > 0) dimension.height += vgap
        dimension.height += rowHeight
    }

    private companion object {
        const val DEFAULT_GAP = 4
    }
}
