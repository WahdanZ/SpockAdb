package spock.adb.ui

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * Lays cards out in as many columns as the width allows, and one when it does not.
 *
 * A single column is right for a tool window docked at 300px and wasteful at 1400: seven cards
 * of buttons run a long way down a wide window, with the whole right-hand half empty. The
 * number of columns follows the width rather than being chosen once.
 *
 * Each card goes to the shortest column, so the two sides finish at roughly the same depth
 * instead of the first column holding everything tall.
 */
class ColumnsLayout(
    /** A column narrower than this is worse than one column. */
    private val minColumnWidth: Int = MIN_COLUMN_WIDTH,
    private val gap: Int = GAP,
) : LayoutManager {

    override fun addLayoutComponent(name: String?, component: Component?) = Unit

    override fun removeLayoutComponent(component: Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = size(parent, parent.width)

    override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, size(parent, 0).height)

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val available = parent.width - insets.left - insets.right
            val columns = columnsFor(available)
            val columnWidth = columnWidth(available, columns)
            val bottoms = IntArray(columns) { insets.top }

            visible(parent).forEach { child ->
                val column = bottoms.indices.minBy { bottoms[it] }
                val x = insets.left + column * (columnWidth + JBUI.scale(gap))
                val height = child.getPreferredSize().height
                child.setBounds(x, bottoms[column], columnWidth, height)
                bottoms[column] += height + JBUI.scale(gap)
            }
        }
    }

    /** The height the cards need at [width], which is what the scroll pane asks for. */
    private fun size(parent: Container, width: Int): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val available = (width.takeIf { it > 0 } ?: parent.width) - insets.left - insets.right
            val columns = columnsFor(available)
            val bottoms = IntArray(columns)

            visible(parent).forEach { child ->
                val column = bottoms.indices.minBy { bottoms[it] }
                bottoms[column] += child.getPreferredSize().height + JBUI.scale(gap)
            }
            val tallest = bottoms.maxOrNull() ?: 0
            return Dimension(0, tallest + insets.top + insets.bottom)
        }
    }

    private fun visible(parent: Container): List<Component> =
        (0 until parent.componentCount).map(parent::getComponent).filter { it.isVisible }

    /** Never fewer than one, however narrow: a column of zero cards shows nothing at all. */
    internal fun columnsFor(available: Int): Int =
        ((available + JBUI.scale(gap)) / (JBUI.scale(minColumnWidth) + JBUI.scale(gap))).coerceAtLeast(1)

    private fun columnWidth(available: Int, columns: Int): Int =
        ((available - (columns - 1) * JBUI.scale(gap)) / columns).coerceAtLeast(1)

    companion object {
        const val MIN_COLUMN_WIDTH = 380
        const val GAP = 8
    }
}
