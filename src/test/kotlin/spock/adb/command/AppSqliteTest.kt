package spock.adb.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSqliteTest {

    @Test
    fun `table names are quoted as identifiers and as shell words`() {
        val command = AppSqlite.rowsCommand("com.example.app", "databases/notes.db", listOf("it's \"odd\""))
        // The identifier doubles its quotes for SQL; the whole script is then shell-quoted twice.
        assertTrue(command.contains("\"\"odd\"\""), command)
        assertTrue(command.startsWith("run-as 'com.example.app' sh -c "), command)
    }

    @Test
    fun `renders each table under its name, as the device printed it`() {
        val text = AppSqlite.render(
            listOf("android_metadata", "notes"),
            listOf(
                "== android_metadata", "rows", "1", "locale", "en_US",
                "== notes", "rows", "2", "id | body", "1 | hi",
            ),
        )
        assertEquals(
            """
            SQLite database · 2 tables · first 20 rows of each, read-only

            android_metadata
              rows
              1
              locale
              en_US

            notes
              rows
              2
              id | body
              1 | hi

            """.trimIndent(),
            text,
        )
    }
}
