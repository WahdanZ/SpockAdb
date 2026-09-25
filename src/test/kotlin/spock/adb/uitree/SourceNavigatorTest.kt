package spock.adb.uitree

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JTree

/**
 * What [SourceNavigator] decides without the IDE: whether a late answer may still be opened, and
 * what a search that breaks turns into.
 */
class SourceNavigatorTest {

    private val tree = JTree()
    private val findings = JList<String>()
    private val inspector = JPanel().apply {
        add(tree)
        add(findings)
    }
    private val editor = JTextField()

    // ---------------------------------------------------------------- autoscroll

    @Test
    fun `an answer is opened while the developer is still in the Inspector`() {
        assertTrue(autoscrollFollows(inspector, showing = true, focusOwner = tree))
        assertTrue(autoscrollFollows(inspector, showing = true, focusOwner = findings), "a finding selects rows too")
        assertTrue(autoscrollFollows(tree, showing = true, focusOwner = tree))
    }

    @Test
    fun `an answer that arrives after the developer left is not opened`() {
        assertFalse(autoscrollFollows(inspector, showing = true, focusOwner = editor), "typing in an editor")
        assertFalse(autoscrollFollows(inspector, showing = true, focusOwner = null), "another window has focus")
        assertFalse(autoscrollFollows(inspector, showing = false, focusOwner = tree), "the tool window was hidden")
        assertFalse(autoscrollFollows(tree, showing = true, focusOwner = findings), "outside a narrower scope")
    }

    // ---------------------------------------------------------------- a search that breaks

    private val query = SourceQuery(text = "Save")

    @Test
    fun `a search that throws becomes an answer saying it failed`() {
        val result = SourceSearch.guarded(query) { throw IllegalStateException("index is corrupt") }

        assertTrue(result.hits.isEmpty())
        assertEquals("index is corrupt", result.failure)
        assertEquals("Source search failed: index is corrupt", SourceStatus.notFound(result))
    }

    @Test
    fun `a failure with no message is named by its class`() {
        val result = SourceSearch.guarded(query) { throw UnsupportedOperationException() }

        assertEquals("UnsupportedOperationException", result.failure)
    }

    @Test
    fun `cancellation is not a failure, so it propagates`() {
        assertThrows(ProcessCanceledException::class.java) {
            SourceSearch.guarded(query) { throw ProcessCanceledException() }
        }
        assertThrows(CancellationException::class.java) {
            SourceSearch.guarded(query) { throw CancellationException() }
        }
    }

    @Test
    fun `a search that finishes is its own answer`() {
        val found = SourceResult(query, null, emptyList(), relativesTried = 2)

        assertSame(found, SourceSearch.guarded(query) { found })
    }
}
