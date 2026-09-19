package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * The tab row of the tool window, which has to survive being docked at 300px.
 *
 * Swing's tabbed pane could not: wrapping stacks seven tabs into four rows and eats the height
 * the window was docked for, and its scrolling layout sends IntelliJ's `DarculaTabbedPaneUI`
 * into an infinite recursion between `ensureSelectedTabIsVisible` and `tabForCoordinate`. This
 * hides what does not fit instead, and never hides the tab being looked at.
 */
class TabStripTest {

    private val titles = listOf("Device", "Storage", "Logcat", "Commands", "UI Inspector", "MCP Server", "Assistant")

    private fun strip(width: Int): TabStrip = TabStrip().apply {
        titles.forEach { addTab(it, JPanel().apply { add(JLabel(it)) }) }
        size = Dimension(width, PREFERRED_HEIGHT)
        doLayout()
    }

    private fun TabStrip.visibleTitles(): List<String> = components
        .filterIsInstance<JToggleButton>()
        .filter { it.isVisible }
        .map { it.text }

    private fun TabStrip.moreButton() = components.first { it !is JToggleButton }

    @Test
    fun `given room, every tab is on the row and More is not`() {
        val strip = strip(WIDE)

        assertEquals(titles.size, strip.visibleTitles().size, strip.visibleTitles().toString())
        assertFalse(strip.moreButton().isVisible, "nothing overflowed, so nothing should offer to show it")
    }

    @Test
    fun `docked narrow, what does not fit goes behind More`() {
        val strip = strip(DOCKED)

        val visible = strip.visibleTitles()
        assertTrue(visible.isNotEmpty(), "at least the selected tab has to be on screen")
        assertTrue(visible.size < titles.size, "seven tabs cannot fit 300px: $visible")
        assertTrue(strip.moreButton().isVisible, "the tabs that did not fit need a way to be reached")
    }

    @Test
    fun `the tab being looked at is never the one hidden`() {
        val strip = strip(DOCKED)

        // The last tab is the first to overflow, so selecting it is the case that matters.
        strip.select("Assistant")
        strip.doLayout()

        assertTrue("Assistant" in strip.visibleTitles(), strip.visibleTitles().toString())
        // Read from where they were laid out, not from the order they were added in: the strip
        // places the selected tab leftmost without moving it in the component list.
        val leftmost = strip.components
            .filterIsInstance<JToggleButton>()
            .filter { it.isVisible }
            .minBy { it.x }
        assertEquals("Assistant", leftmost.text, "it is placed at the front of the run")
    }

    @Test
    fun `selecting a tab shows its content and says so`() {
        var announced: String? = null
        val strip = TabStrip().apply {
            onSelected = { announced = it }
            addTab("Device", JPanel().apply { name = "Device" })
            addTab("Storage", JPanel().apply { name = "Storage" })
            size = Dimension(WIDE, PREFERRED_HEIGHT)
            doLayout()
        }

        strip.select("Storage")
        strip.content.doLayout()

        assertEquals("Storage", announced)
        assertEquals(
            "Storage",
            strip.content.components.single { it.isVisible }.name,
            "the card for the selected tab is the one showing",
        )
    }

    @Test
    fun `the row asks for one line of height and no width of its own`() {
        val strip = strip(WIDE)

        assertEquals(0, strip.preferredSize.width, "a width here would stop the tool window narrowing")
        assertTrue(strip.preferredSize.height > 0)
    }

    private companion object {
        /** A tool window docked on the left is routinely this narrow. */
        const val DOCKED = 300
        const val WIDE = 1400
        const val PREFERRED_HEIGHT = 30
    }
}
