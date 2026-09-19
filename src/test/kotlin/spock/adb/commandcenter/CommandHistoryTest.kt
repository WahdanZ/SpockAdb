package spock.adb.commandcenter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandHistoryTest {

    @Test
    fun `most recent command comes first`() {
        val history = CommandHistory()
        history.record("pm list packages")
        history.record("dumpsys battery")

        assertEquals(listOf("dumpsys battery", "pm list packages"), history.commands())
    }

    @Test
    fun `re-running a command moves it to the front instead of duplicating it`() {
        val history = CommandHistory()
        history.record("a")
        history.record("b")
        history.record("a")

        assertEquals(listOf("a", "b"), history.commands())
    }

    @Test
    fun `history is bounded and drops the oldest`() {
        val history = CommandHistory(maxRecent = 3)
        listOf("1", "2", "3", "4").forEach(history::record)

        assertEquals(listOf("4", "3", "2"), history.commands())
    }

    @Test
    fun `blank commands are not recorded`() {
        val history = CommandHistory()
        history.record("   ")
        history.record("")

        assertTrue(history.commands().isEmpty())
    }

    @Test
    fun `commands are trimmed so whitespace does not create duplicates`() {
        val history = CommandHistory()
        history.record("ps -A")
        history.record("  ps -A  ")

        assertEquals(listOf("ps -A"), history.commands())
    }

    @Test
    fun `toggling a favourite adds then removes it`() {
        val history = CommandHistory()

        assertTrue(history.toggleFavourite("dumpsys battery"))
        assertTrue(history.isFavourite("dumpsys battery"))

        assertFalse(history.toggleFavourite("dumpsys battery"))
        assertFalse(history.isFavourite("dumpsys battery"))
    }

    @Test
    fun `favourites survive clearing recent commands`() {
        val history = CommandHistory()
        history.record("ps -A")
        history.toggleFavourite("ps -A")

        history.clearRecent()

        assertTrue(history.commands().isEmpty())
        assertEquals(listOf("ps -A"), history.favourites())
    }

    @Test
    fun `loading persisted state drops blanks and duplicates`() {
        val history = CommandHistory()
        history.load(runs("a", "  a  ", "", "b"), listOf("fav", "fav", " "))

        assertEquals(listOf("a", "b"), history.commands())
        assertEquals(listOf("fav"), history.favourites())
    }

    @Test
    fun `loading respects the recent limit`() {
        val history = CommandHistory(maxRecent = 2)
        history.load(runs("1", "2", "3"), emptyList())

        assertEquals(listOf("1", "2"), history.commands())
    }

    private fun CommandHistory.commands(): List<String> = recent().map { it.command }

    private fun runs(vararg commands: String): List<CommandHistory.Run> =
        commands.mapIndexed { index, command -> CommandHistory.Run(command, index.toLong()) }

    @Test
    fun `a run records when it happened, so history can be read in time order`() {
        val history = CommandHistory()
        history.record("first", at = 1_000)
        history.record("second", at = 2_000)

        assertEquals(listOf(2_000L, 1_000L), history.recent().map { it.at })

        // Re-running moves it to the front and brings its new time with it.
        history.record("first", at = 3_000)
        assertEquals(listOf("first" to 3_000L, "second" to 2_000L), history.recent().map { it.command to it.at })
    }
}
