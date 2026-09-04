package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Guards the fix for the Devices tab being clipped.
 *
 * A plain panel inside a `JScrollPane` is laid out at its *preferred* width, so a two-column
 * grid sized itself from the widest label and the right-hand column was cut off with a
 * horizontal scrollbar. These properties are what make it shrink to the tool window instead.
 */
class VerticallyScrollablePanelTest {

    @Test
    fun `tracks the viewport width so content shrinks rather than clipping`() {
        assertTrue(VerticallyScrollablePanel().scrollableTracksViewportWidth)
    }

    @Test
    fun `does not track the viewport height, so the pane still scrolls vertically`() {
        assertFalse(VerticallyScrollablePanel().scrollableTracksViewportHeight)
    }

    @Test
    fun `a two-column grid is laid out at the viewport width, not its preferred width`() {
        val panel = VerticallyScrollablePanel(GridLayout(0, 2, 4, 4)).apply {
            // Labels wide enough that the natural preferred width exceeds a docked tool window.
            repeat(4) { add(JButton("A fairly long button label $it")) }
        }
        val preferredWidth = panel.preferredSize.width

        val scrollPane = JScrollPane(panel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            size = Dimension(TOOL_WINDOW_WIDTH, TOOL_WINDOW_HEIGHT)
            doLayout()
            viewport.doLayout()
        }

        assertTrue(
            preferredWidth > TOOL_WINDOW_WIDTH,
            "fixture should be wider than the viewport, was $preferredWidth",
        )
        assertEquals(
            scrollPane.viewport.width,
            panel.width,
            "the panel must be laid out at the viewport width, not $preferredWidth",
        )
    }

    @Test
    fun `buttons with a zero minimum width can shrink below their label`() {
        // Without this a button refuses to shrink, and two side by side force the whole
        // panel wider than the tool window no matter what the scroll pane does.
        val button = JButton("A fairly long button label")
        button.minimumSize = Dimension(0, button.preferredSize.height)

        assertEquals(0, button.minimumSize.width)
        assertTrue(button.preferredSize.width > 0)
    }

    private companion object {
        /** A tool window docked on the left is routinely this narrow. */
        const val TOOL_WINDOW_WIDTH = 300
        const val TOOL_WINDOW_HEIGHT = 600
    }
}
