package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JPanel

/**
 * How many columns of cards a given width is worth.
 *
 * One column is right for a tool window docked at 300px and wasteful at 1400, where seven cards
 * run a long way down with the right-hand half of the window empty.
 */
class ColumnsLayoutTest {

    private fun card(height: Int) = JPanel().apply { preferredSize = Dimension(200, height) }

    private fun panel(width: Int, vararg heights: Int): JPanel {
        val layout = ColumnsLayout()
        return JPanel(layout).apply {
            heights.forEach { add(card(it)) }
            size = Dimension(width, TALL)
            doLayout()
        }
    }

    @Test
    fun `a docked tool window gets one column`() {
        assertEquals(1, ColumnsLayout().columnsFor(DOCKED))
        assertEquals(1, ColumnsLayout().columnsFor(0), "never fewer than one, however narrow")
    }

    @Test
    fun `a wide window gets more`() {
        assertEquals(2, ColumnsLayout().columnsFor(WIDE))
        assertEquals(3, ColumnsLayout().columnsFor(VERY_WIDE))
    }

    @Test
    fun `in one column the cards are stacked, each the full width`() {
        val panel = panel(DOCKED, 100, 100, 100)

        val xs = panel.components.map { it.x }.distinct()
        assertEquals(1, xs.size, "every card starts at the same x")
        assertTrue(panel.components.all { it.width > DOCKED / 2 }, "a card should fill the column")
        assertTrue(panel.components[1].y > panel.components[0].y, "the second card is below the first")
    }

    @Test
    fun `a tall card does not drag the whole of one column down with it`() {
        // Shortest-column-first: the tall card takes one side, and the three short ones share
        // the other rather than queueing behind it.
        val panel = panel(WIDE, 300, 60, 60, 60)

        val columns = panel.components.map { it.x }.distinct().sorted()
        assertEquals(2, columns.size)
        val second = panel.components.drop(1)
        assertTrue(second.all { it.x == columns[1] }, "the short cards went to the empty column")
    }

    @Test
    fun `the height asked for is the tallest column, not the sum of the cards`() {
        val panel = panel(WIDE, 100, 100)

        // Side by side, so the panel is about one card tall rather than two.
        assertTrue(panel.preferredSize.height < 2 * 100, "was ${panel.preferredSize.height}")
        assertEquals(0, panel.preferredSize.width, "a width here would stop the tool window narrowing")
    }

    private companion object {
        const val DOCKED = 300
        const val WIDE = 900
        const val VERY_WIDE = 1300
        const val TALL = 2000
    }
}
