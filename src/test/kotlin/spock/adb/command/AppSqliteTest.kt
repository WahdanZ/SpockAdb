package spock.adb.command

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class AppSqliteTest {

    private val mark = "spock-0123456789abcdef0123456789abcdef"

    @Test
    fun `table names are listed as hex, and only SQLite's own tables are left out`() {
        val command = AppSqlite.tablesCommand("com.example.app", "databases/notes.db")
        assertTrue(command.contains("hex(name)"), command)
        assertTrue(command.contains("substr(name, 1, 7) <> "), command)
        assertFalse(command.contains("LIKE"), command)
    }

    @Test
    fun `hex names decode whole, newlines and edge spaces included`() {
        val names = listOf("notes", " spaced ", "two\nlines", "sqliteusers", "ünï")
        val lines = names.map { name -> name.toByteArray().joinToString("") { "%02X".format(it) } }
        assertEquals(names, AppSqlite.tableNames(lines + "Error: not hex"))
    }

    @Test
    fun `tables are marked by index and the mark, never by name`() {
        val command = AppSqlite.rowsCommand("com.example.app", "databases/notes.db", listOf("it's \"odd\" \\n"), mark)
        // The identifier doubles its quotes for SQL; the whole script is then shell-quoted twice.
        assertTrue(command.contains("\"\"odd\"\""), command)
        assertTrue(command.startsWith("run-as 'com.example.app' sh -c "), command)
        assertTrue(command.contains("$mark 0"), command)
        assertTrue(command.replace("'\\''", "'").contains("-separator ' | ' -nullvalue NULL"), command)
        assertFalse(command.contains("echo '"), command)
    }

    @Test
    fun `a path starting with a dash is not read as an option`() {
        assertTrue(AppSqlite.tablesCommand("com.example.app", "-x.db").contains("./-x.db"))
        assertTrue(AppSqlite.rowsCommand("com.example.app", "-x.db", listOf("t"), mark).contains("./-x.db"))
        assertFalse(AppSqlite.tablesCommand("com.example.app", "databases/-x.db").contains("./"))
    }

    @Test
    fun `sections keep blank rows, ignore lookalike headings and carry each exit status`() {
        val output = listOf(
            "$mark 0", "rows", "3", "body", "== spoofed heading", "", "spock-ffff 1 rc=0", "$mark 0 rc=0",
            "$mark 1", "Error: in prepare, no such table: gone", "$mark 1 rc=1",
            "rc=0",
        ).joinToString("\r\n")
        val sections = AppSqlite.sections(output, mark, listOf("notes", "gone", "never"))

        assertEquals(listOf("rows", "3", "body", "== spoofed heading", "", "spock-ffff 1 rc=0"), sections[0].lines)
        assertNull(sections[0].failure)
        assertEquals("Error: in prepare, no such table: gone", sections[1].failure)
        assertEquals("the device stopped before this table was read", sections[2].failure)
    }

    @Test
    fun `renders each table under its name, and a failed one as a failure`() {
        val text = AppSqlite.render(
            2,
            listOf(
                AppSqlite.Section("notes", listOf("rows", "2", "id | body", "1 | hi", "2 | NULL"), null),
                AppSqlite.Section("lo\ncked", emptyList(), "Error: database is locked"),
            ),
        )
        assertEquals(
            """
            SQLite database · 2 tables · first 20 rows of each, read-only

            notes
              rows
              2
              id | body
              1 | hi
              2 | NULL

            lo�cked
              could not read: Error: database is locked

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `control characters are replaced and a long table is cut`() {
        val rows = listOf("a\u001b[2Jb") + List(100) { "x".repeat(1000) }
        val text = AppSqlite.render(1, listOf(AppSqlite.Section("t", rows, null)))

        assertTrue(text.contains("  a�[2Jb\n"), text)
        assertTrue(text.lines().all { it.length <= AppSqlite.MAX_LINE_CHARS + 3 }, "a line ran past the cap")
        assertTrue(text.length < AppSqlite.MAX_TABLE_CHARS + 1000, "${text.length} characters")
        assertTrue(text.trimEnd().endsWith("more lines not shown"), text.takeLast(80))
    }

    @Test
    fun `a table that fails is reported as a failure, not as rows`() {
        val device = device { command ->
            if (command.contains("hex(name)")) {
                "6E6F746573\n676F6E65\nrc=0\n"
            } else {
                val mark = Regex("spock-[0-9a-f]{32}").find(command)!!.value
                "$mark 0\nrows\n1\n$mark 0 rc=0\n$mark 1\nError: in prepare, no such table: gone\n$mark 1 rc=1\nrc=0\n"
            }
        }
        val read = AppSqliteCommand().execute(request, project, device) as SqliteTables.Read

        assertEquals(1, read.unreadable)
        assertTrue(read.text.contains("gone\n  could not read: Error: in prepare, no such table: gone"), read.text)
        assertEquals(
            "databases/notes.db: 1 of 2 tables read on the device, read-only. 1 could not be read.",
            AppSqlite.shown(request.path, read).second,
        )
    }

    @Test
    fun `a database that cannot be opened fails the whole read`() {
        val said = "Error: unable to open database \"databases/notes.db\": unable to open database file"
        val device = device { "$said\nrc=1\n" }
        val failure = assertThrows<IllegalStateException> { AppSqliteCommand().execute(request, project, device) }
        assertTrue(failure.message!!.contains("unable to open database file"), failure.message)
    }

    @Test
    fun `no sqlite3 and no tables are told apart`() {
        assertEquals(SqliteTables.NoSqlite, AppSqliteCommand().execute(request, project, device { "rc=127\n" }))
        assertEquals(SqliteTables.NoTables, AppSqliteCommand().execute(request, project, device { "rc=0\n" }))
        assertTrue(AppSqlite.shown("a.db", SqliteTables.NoSqlite).second.contains("no sqlite3"))
        assertTrue(AppSqlite.shown("a.db", SqliteTables.NoTables).second.contains("no tables"))
    }

    @Test
    fun `a database is recognised by its header on the device, before any size check or download`() {
        val command = StorageTree.readFileCommand("com.example.app", "databases/big.db")
        val header = command.indexOf("head -c 16")
        assertTrue(header in 0 until command.indexOf("wc -c"), command)
        assertTrue(header < command.indexOf("base64 \"\$f\"; echo"), command)
        assertTrue(command.contains("U1FMaXRlIGZvcm1hdCAzAA=="), command)
        assertTrue(StorageTree.readFileCommand("com.example.app", "-x.db").contains("./-x.db"))
    }

    @Test
    fun `the device's word that a file is a database is taken without the file`() {
        val sqlite = device { "rc=${StorageTree.STATUS_SQLITE}\n" }.readAppFile("com.example.app", "databases/big.db")
        assertEquals(AppFileContent.Sqlite, sqlite)

        val text = device { "aGk=\nrc=0\n" }.readAppFile("com.example.app", "files/a.txt")
        assertEquals("hi", ((text as AppFileContent.Bytes).bytes).decodeToString())
    }

    @Test
    fun `while the tables are read the view says so, and a failure replaces it`() {
        assertEquals("SQLite database — reading tables…", AppSqlite.READING)
        val (text, status) = AppSqlite.failed("databases/notes.db", IllegalStateException("Error: database is locked"))
        assertTrue(text.startsWith("SQLite database — could not read its tables.\n"), text)
        assertTrue(text.endsWith("Error: database is locked"), text)
        assertEquals("Error: database is locked", status)
    }

    private val request = AppSqliteRequest("com.example.app", "databases/notes.db")
    private val project = mockk<Project>(relaxed = true)

    /** A device whose shell answers each command with [reply]. */
    private fun device(reply: (String) -> String): IDevice = mockk<IDevice>().also { device ->
        val command = slot<String>()
        val receiver = slot<IShellOutputReceiver>()
        every {
            device.executeShellCommand(capture(command), capture(receiver), any(), any<TimeUnit>())
        } answers {
            val bytes = reply(command.captured).toByteArray()
            receiver.captured.addOutput(bytes, 0, bytes.size)
            receiver.captured.flush()
        }
    }
}
