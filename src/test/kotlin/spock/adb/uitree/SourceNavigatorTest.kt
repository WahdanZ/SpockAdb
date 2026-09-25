package spock.adb.uitree

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

/**
 * What [SourceNavigator] decides without the IDE: what a search that breaks turns into.
 */
class SourceNavigatorTest {

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
