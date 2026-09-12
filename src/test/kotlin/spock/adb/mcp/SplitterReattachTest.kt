package spock.adb.mcp

import com.intellij.ui.OnePixelSplitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Pins the Swing behaviour behind the MCP panel's collapsing body, and the shape of the fix.
 *
 * [McpServerPanel] moves two components — the Activity/Tools tabs and the Details section —
 * between two parents as the tool window is resized: into a splitter when there is room, and
 * stacked directly in the body panel when there is not.
 *
 * Re-parenting a component detaches it from the splitter's container but leaves the splitter
 * still referencing it, and the splitter ignores a component it believes it already holds. So
 * returning to the split arrangement silently dropped the tabs: `bodyPanel.removeAll()` took
 * them out, and handing them back to the splitter did nothing. The panel rendered an empty
 * body — no tabs, and no Search/Tool/outcome filter row — while Details, whose reference *was*
 * being cleared, kept working. The fix is to clear both references on the way out.
 */
class SplitterReattachTest {

    @Test
    fun `a splitter ignores a component it still references`() {
        // The trap. Documented as a test because the panel's correctness depends on it, and
        // nothing about the setter call site suggests it can do nothing at all.
        onEdt {
            val splitter = OnePixelSplitter(true, PROPORTION)
            val tabs = JPanel()
            val stacked = JPanel(BorderLayout())

            splitter.firstComponent = tabs
            stacked.add(tabs, BorderLayout.CENTER)
            splitter.firstComponent = tabs

            assertEquals(1, stacked.componentCount, "the splitter was expected to ignore the hand-back")
        }
    }

    @Test
    fun `clearing the reference first lets the splitter take the component back`() {
        // What McpServerPanel.applyDensity does when it leaves the stacked arrangement.
        onEdt {
            val splitter = OnePixelSplitter(true, PROPORTION)
            val tabs: JComponent = JPanel()
            val details: JComponent = JPanel()
            val stacked = JPanel(BorderLayout())

            splitter.firstComponent = tabs
            splitter.secondComponent = details

            // Leaving SPLIT: clear both, then re-parent.
            splitter.firstComponent = null
            splitter.secondComponent = null
            stacked.add(tabs, BorderLayout.CENTER)
            stacked.add(details, BorderLayout.SOUTH)
            assertEquals(2, stacked.componentCount, "both should be stacked")

            // Returning to SPLIT, as applyDensity does after bodyPanel.removeAll().
            stacked.removeAll()
            splitter.firstComponent = tabs
            splitter.secondComponent = details

            assertNotNull(tabs.parent, "the tabs must be attached, or the body renders empty")
            assertNotNull(details.parent, "Details must be attached")
            assertEquals(0, stacked.componentCount, "nothing should be left behind in the stacked parent")
        }
    }

    @Test
    fun `a cleared splitter reports no components`() {
        onEdt {
            val splitter = OnePixelSplitter(true, PROPORTION)
            splitter.firstComponent = JPanel()
            splitter.secondComponent = JPanel()

            splitter.firstComponent = null
            splitter.secondComponent = null

            assertNull(splitter.firstComponent, "first should be clear")
            assertNull(splitter.secondComponent, "second should be clear")
        }
    }

    private fun onEdt(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val PROPORTION = 0.6f
    }
}
