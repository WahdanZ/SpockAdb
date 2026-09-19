package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.NetworkToggleRow
import spock.adb.command.Network
import spock.adb.mcp.McpActivityTable
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * What the tool window's panels do when it is docked at the width developers actually dock it.
 *
 * A tool window sharing the screen with code is routinely 300px wide. Everything here is a
 * component that decides its own widths, so each one is a place where a number chosen while
 * looking at an undocked window quietly clips something.
 */
class NarrowToolWindowTest {

    @Test
    fun `the activity table keeps every column readable at a docked width`() {
        val table = McpActivityTable()
        val pane = JScrollPane(table).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            size = Dimension(TOOL_WINDOW_WIDTH, TOOL_WINDOW_HEIGHT)
            doLayout()
            viewport.doLayout()
        }

        assertTrue(
            table.minimumSize.width <= TOOL_WINDOW_WIDTH,
            "the table demands ${table.minimumSize.width}px, more than a docked tool window has",
        )
        assertTrue(pane.viewport.width > 0)
    }

    @Test
    fun `a network row fits, and shrinks no further than its controls need`() {
        val row = NetworkToggleRow(Network.WIFI, "Wi-Fi")

        assertTrue(
            row.minimumSize.width <= TOOL_WINDOW_WIDTH,
            "the row demands ${row.minimumSize.width}px, more than a docked tool window has",
        )
    }

    @Test
    fun `a network row never grows taller than one line of controls`() {
        val row = NetworkToggleRow(Network.MOBILE, "Mobile data")

        // Inside a BoxLayout column an unbounded maximum height makes a row stretch and the
        // section below it drift down the panel.
        assertTrue(row.maximumSize.height < TOOL_WINDOW_HEIGHT, "height was ${row.maximumSize.height}")
        assertEquals(Int.MAX_VALUE, row.maximumSize.width, "the row should still take the full width")
    }

    private companion object {
        /** A tool window docked on the left is routinely this narrow. */
        const val TOOL_WINDOW_WIDTH = 300
        const val TOOL_WINDOW_HEIGHT = 600
    }
}
