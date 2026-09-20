package spock.adb.logcat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The row view's contract with the panel above it.
 *
 * The editor view answers the same questions and cannot be built without a `Project`, so its
 * equivalents are covered by [LogcatLineTest] and by the builder tests; what is checked here is
 * the shared contract — a cap that both views must honour, and the distinction between a
 * selection and a mere focus that "Ask AI" depends on.
 */
class LogcatListViewTest {

    private fun entry(message: String) =
        LogcatEntry("09-20 17:01:16.753", 3189, 3189, LogLevel.INFO, "Tag", message, "raw $message")

    private fun entries(count: Int) = (1..count).map { entry("line $it") }

    @Test
    fun `setAll honours the visible limit`() {
        // The incremental path trimmed; this one did not, so a filter change or a view switch
        // could render the whole 20,000-line buffer — twice the limit — in one go.
        val view = LogcatListView()

        view.setAll(entries(12_000), autoScroll = false)

        assertEquals(10_000, view.size())
        // The newest lines are the ones kept.
        assertTrue(view.entries().last().message == "line 12000")
    }

    @Test
    fun `append stays inside the visible limit`() {
        val view = LogcatListView()

        view.setAll(entries(9_900), autoScroll = false)
        view.append(entries(500), autoScroll = false)

        assertEquals(10_000, view.size())
    }

    @Test
    fun `nothing is selected until something is selected`() {
        val view = LogcatListView()
        view.setAll(entries(5), autoScroll = false)

        assertTrue(view.selection().isEmpty())
        assertTrue(view.focus().entries.isEmpty())
        assertEquals(null, view.focus().singleIndex)
    }

    @Test
    fun `clearing empties the view`() {
        val view = LogcatListView()
        view.setAll(entries(5), autoScroll = false)

        view.clear()

        assertEquals(0, view.size())
        assertTrue(view.entries().isEmpty())
    }
}
