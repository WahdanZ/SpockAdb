package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.models.BackStackData

/**
 * What the Activity Stack popup actually puts in front of a developer.
 *
 * The popup used to be handed strings the controller had formatted — `\t0-com.example.app` for a
 * task and `\t\t\t\t0-` for an activity `dumpsys` had not given up — so the parser's indexes and
 * its failures were both user-facing text. These pin the rows that replaced them.
 */
class ActivityStackRowTest {

    private fun rows(vararg tasks: BackStackData) = tasks.toList().toActivityStackRows()

    private fun rows(labels: Map<String, String>, vararg tasks: BackStackData) =
        tasks.toList().toActivityStackRows(labels)

    @Test
    fun `a task is followed by its own activities`() {
        val result = rows(
            BackStackData("com.example.app", listOf("com.example.app.DetailActivity", "com.example.app.ListActivity")),
        )

        assertEquals(
            listOf("com.example.app", "DetailActivity", "ListActivity"),
            result.map { it.displayText() },
        )
    }

    @Test
    fun `a task with no readable activity says so instead of showing an empty row`() {
        val result = rows(BackStackData("com.wahdanz.kanban", emptyList()))

        assertEquals(listOf("com.wahdanz.kanban", "No resumed activity"), result.map { it.displayText() })
    }

    @Test
    fun `a blank activity name never reaches the popup as a row of its own`() {
        // Defensive: the parser drops these, but an empty string leaking through used to render
        // as the literal text `0-`.
        val result = rows(BackStackData("com.example.app", listOf("", "com.example.app.MainActivity", "   ")))

        assertEquals(listOf("com.example.app", "MainActivity"), result.map { it.displayText() })
    }

    @Test
    fun `no row text carries a parser index`() {
        val result = rows(
            BackStackData("com.example.app", listOf("com.example.app.MainActivity"), isForeground = true),
            BackStackData("com.android.launcher3", emptyList()),
        )

        assertTrue(result.none { it.displayText().matches(Regex("""^\s*\d+-.*""")) }, result.toString())
    }

    @Test
    fun `an activity from another package keeps its full name`() {
        val result = rows(
            BackStackData("com.example.app", listOf("com.android.chooser.ResolverActivity")),
        )

        assertEquals("com.android.chooser.ResolverActivity", result.last().displayText())
    }

    @Test
    fun `a nested activity keeps the part that is not the package`() {
        val result = rows(BackStackData("com.example.app", listOf("com.example.app.ui.MainActivity")))

        assertEquals("ui.MainActivity", result.last().displayText())
    }

    @Test
    fun `full names stay reachable through the tooltip`() {
        val result = rows(BackStackData("com.example.app", listOf("com.example.app.MainActivity")))

        assertEquals("com.example.app", result.first().tooltip())
        assertEquals("com.example.app.MainActivity", result.last().tooltip())
    }

    @Test
    fun `only activity rows name a class to open`() {
        val result = rows(
            BackStackData("com.example.app", listOf("com.example.app.MainActivity")),
            BackStackData("com.wahdanz.kanban", emptyList()),
        )

        assertEquals(
            listOf(null, "com.example.app.MainActivity", null, null),
            result.map { it.className() },
        )
        assertNull(result.last().tooltip())
    }

    @Test
    fun `the foreground task is marked on the task row`() {
        val result = rows(
            BackStackData("com.wahdanz.kanban", emptyList(), isForeground = true),
            BackStackData("com.example.app", listOf("com.example.app.MainActivity")),
        )

        val tasks = result.filterIsInstance<ActivityStackRow.Task>()
        assertTrue(tasks.first().isForeground)
        assertFalse(tasks.last().isForeground)
    }

    @Test
    fun `a task reads as its app name over its package when the device gave one up`() {
        val result = rows(
            mapOf("com.example.myapplication" to "My Application"),
            BackStackData("com.example.myapplication", listOf("com.example.myapplication.MainActivity")),
        )

        assertEquals("My Application", result.first().displayText())
        assertEquals("com.example.myapplication", result.first().secondaryText())
    }

    @Test
    fun `a task with no app name is still just its package, on one line`() {
        val result = rows(BackStackData("com.android.settings", emptyList()))

        assertEquals("com.android.settings", result.first().displayText())
        assertNull(result.first().secondaryText())
    }

    @Test
    fun `an app name that only repeats the package is not shown twice`() {
        val result = rows(
            mapOf("com.example.app" to "com.example.app", "com.other.app" to "  "),
            BackStackData("com.example.app", emptyList()),
            BackStackData("com.other.app", emptyList()),
        )

        assertEquals(
            listOf("com.example.app", "com.other.app"),
            result.filterIsInstance<ActivityStackRow.Task>().map { it.displayText() },
        )
        assertTrue(result.all { it.secondaryText() == null })
    }

    @Test
    fun `no row but a named task carries a second line`() {
        val result = rows(
            mapOf("com.example.app" to "My Application"),
            BackStackData("com.example.app", listOf("com.example.app.MainActivity")),
        )

        assertEquals(listOf("com.example.app", null), result.map { it.secondaryText() })
    }

    @Test
    fun `an empty stack produces no rows`() {
        assertTrue(rows().isEmpty())
    }
}
