package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellQuote
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.StorageFileView
import java.util.UUID

/** A SQLite database in an app's data directory, for the storage tree's read-only view. */
class AppSqliteRequest(val packageName: String, val path: String)

/** What reading a database came to; each says something different on the status line. */
sealed interface SqliteTables {

    /** The device has no `sqlite3`, so nothing was read. */
    object NoSqlite : SqliteTables

    /** `sqlite3` opened the database and it holds no tables of its own. */
    object NoTables : SqliteTables

    /**
     * [text] is the view: the first [shown] of [tables], of which [unreadable] could not be
     * read and say why in it.
     */
    data class Read(val text: String, val tables: Int, val shown: Int, val unreadable: Int) : SqliteTables
}

/**
 * The tables of a SQLite database, each with its row count and first rows.
 *
 * Read with the device's own `sqlite3` as the app, through `run-as`, opened `-readonly` so a
 * running app's writes and locks are untouched. Many physical devices ship without `sqlite3`;
 * then the view says so rather than failing.
 *
 * Two round trips: the table names, then every table's rows in one script. That script runs
 * under the storage shell timeout, which ddmlib applies to silence rather than to the whole
 * call, so one table that keeps `sqlite3` quiet for that long fails the read of all of them.
 * [AppSqlite.MAX_TABLES] tables of [AppSqlite.MAX_ROWS] rows keep it short.
 */
class AppSqliteCommand : Command<AppSqliteRequest, SqliteTables> {

    override fun execute(p: AppSqliteRequest, project: Project, device: IDevice): SqliteTables {
        ShellQuote.requireValidComponent(p.packageName, "Package name")
        AppStoragePaths.requireBrowsable(p.path)
        val doing = "read the tables of ${p.path}"
        val listing = RunAs.classify(device.runAsShell(AppSqlite.tablesCommand(p.packageName, p.path)))
        if (listing is RunAsOutcome.Failed && listing.status == AppSqlite.NO_SQLITE) return SqliteTables.NoSqlite
        check(listing is RunAsOutcome.Succeeded) { AppStorageShell.failureMessage(p.packageName, doing, listing) }

        val tables = AppSqlite.tableNames(listing.lines)
        if (tables.isEmpty()) return SqliteTables.NoTables
        val shown = tables.take(AppSqlite.MAX_TABLES)
        val mark = AppSqlite.newMark()
        val output = device.runAsShell(AppSqlite.rowsCommand(p.packageName, p.path, shown, mark))
        val outcome = RunAs.classify(output)
        check(outcome is RunAsOutcome.Succeeded) { AppStorageShell.failureMessage(p.packageName, doing, outcome) }

        val sections = AppSqlite.sections(output, mark, shown)
        val unreadable = sections.count { it.failure != null }
        return SqliteTables.Read(AppSqlite.render(tables.size, sections), tables.size, shown.size, unreadable)
    }
}

internal object AppSqlite {

    const val NO_SQLITE = 127
    const val MAX_TABLES = 20
    const val MAX_ROWS = 20

    /** What one table may add to the view, and one line of it, before the rest is cut. */
    const val MAX_TABLE_CHARS = 8_000
    const val MAX_LINE_CHARS = 400

    const val NO_SQLITE_MESSAGE =
        "SQLite database. This device has no sqlite3 command, so its tables cannot be read here. " +
            "Emulators have it; on a phone, pull the file and open it on your computer."

    /** What the view shows while the tables are read, in place of the file it never downloads. */
    const val READING = "SQLite database — reading tables…"

    /** What replaces [READING] when the tables could not be read, and the status line with it. */
    fun failed(path: String, failure: Throwable): Pair<String, String> {
        val message = failure.message ?: "Could not read the tables of $path."
        return "SQLite database — could not read its tables.\n\n$message" to message
    }

    /** What the view shows for [tables] of the database at [path], and what the status line says. */
    fun shown(path: String, tables: SqliteTables): Pair<String, String> = when (tables) {
        SqliteTables.NoSqlite -> NO_SQLITE_MESSAGE to "$path: this device has no sqlite3 to read it with."
        SqliteTables.NoTables -> "SQLite database with no tables." to "$path: a SQLite database with no tables."
        is SqliteTables.Read -> {
            val noun = if (tables.tables == 1) "table" else "tables"
            val read = "${tables.shown - tables.unreadable} of ${tables.tables} $noun"
            val failed = if (tables.unreadable > 0) " ${tables.unreadable} could not be read." else ""
            tables.text to "$path: $read read on the device, read-only.$failed"
        }
    }

    /** One table's part of the rows script: [failure] is null when `sqlite3` exited 0. */
    class Section(val name: String, val lines: List<String>, val failure: String?)

    /**
     * Every table's name, hex-encoded so a name holding a newline or edge spaces comes back
     * whole. SQLite's own tables all start `sqlite_`; a table merely starting `sqlite` is the app's.
     */
    fun tablesCommand(packageName: String, path: String): String = RunAs.command(
        packageName,
        "f=${ShellQuote.quote(StorageTree.argument(path))}; " +
            "if ! command -v sqlite3 >/dev/null 2>&1; then echo rc=$NO_SQLITE; else " +
            "sqlite3 -readonly \"\$f\" ${ShellQuote.quote(TABLES_SQL)} 2>&1; echo rc=\$?; fi",
    )

    /** The names [tablesCommand] printed, decoded; anything that is not hex is `sqlite3` talking. */
    fun tableNames(lines: List<String>): List<String> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.length % 2 == 0 && it.all { c -> c in HEX_DIGITS } }
        .map { hex -> hex.chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray().decodeToString() }

    /**
     * One `sqlite3` per table, so one unreadable table cannot hide the rest, each framed by
     * lines carrying [mark] and the table's index, the closing one with `sqlite3`'s exit status.
     *
     * The mark is fresh for every read: a row is the app's data, and data can say anything,
     * but it cannot guess a random mark. Indexes rather than names keep a name off the shell's
     * `echo`, which would read its backslashes.
     *
     * `-quote` prints values as SQL literals: a BLOB as `X'…'` hex rather than raw bytes that
     * garble the view and break its lines, and NULL apart from an empty string (`NULL` vs `''`).
     * Checked on the emulator's sqlite3 3.39 against a 13 MB database of 96-byte blobs.
     */
    fun rowsCommand(packageName: String, path: String, tables: List<String>, mark: String): String {
        val perTable = tables.withIndex().joinToString(" ") { (index, table) ->
            val name = "\"" + table.replace("\"", "\"\"") + "\""
            val sql = "SELECT count(*) AS rows FROM $name; SELECT * FROM $name LIMIT $MAX_ROWS;"
            "printf '%s\\n' '$mark $index'; " +
                "sqlite3 -readonly -quote -header -separator ' | ' \"\$f\" ${ShellQuote.quote(sql)} 2>&1; " +
                "printf '%s\\n' \"$mark $index rc=\$?\";"
        }
        return RunAs.command(packageName, "f=${ShellQuote.quote(StorageTree.argument(path))}; $perTable echo rc=0")
    }

    /** A mark no row can hold by chance, nor by design: the app never sees it before it is used. */
    fun newMark(): String = "spock-" + UUID.randomUUID().toString().replace("-", "")

    /**
     * Each table's lines, cut out of [output] by [mark]. Read from the raw output rather than
     * [RunAs.classify]'s lines, which drop blank ones — and a row of empty cells is blank.
     */
    fun sections(output: String, mark: String, tables: List<String>): List<Section> {
        val lines = output.lines().map { it.trimEnd('\r') }
        return tables.mapIndexed { index, name ->
            val start = lines.indexOf("$mark $index")
            val end = lines.withIndex().firstOrNull { (at, line) -> at > start && line.startsWith("$mark $index rc=") }
            when {
                start < 0 || end == null -> Section(name, emptyList(), "the device stopped before this table was read")
                else -> {
                    val body = lines.subList(start + 1, end.index)
                    val status = end.value.substringAfter(" rc=").trim().toIntOrNull()
                    val said = body.filter { it.isNotBlank() }.joinToString(" ").trim()
                    val failure = if (status == 0) null else said.ifEmpty { "sqlite3 exited with $status" }
                    Section(name, body, failure)
                }
            }
        }
    }

    fun render(total: Int, sections: List<Section>): String = buildString {
        append("SQLite database · $total table${if (total == 1) "" else "s"}")
        if (total > sections.size) append(" (first ${sections.size} shown)")
        append(" · first $MAX_ROWS rows of each, read-only\n")
        sections.forEach { section ->
            append("\n").append(line(section.name)).append("\n")
            if (section.failure != null) {
                append("  could not read: ").append(line(section.failure)).append("\n")
            } else {
                appendRows(section.lines)
            }
        }
    }

    /** A table's rows, until the table has used up [MAX_TABLE_CHARS]. */
    private fun StringBuilder.appendRows(rows: List<String>) {
        var used = 0
        rows.forEachIndexed { index, row ->
            val text = line(row)
            if (used + text.length > MAX_TABLE_CHARS) {
                append("  … ${rows.size - index} more lines not shown\n")
                return
            }
            used += text.length
            append("  ").append(text).append("\n")
        }
    }

    /** One line of what `sqlite3` printed, or a table's name: control characters replaced, a long one cut. */
    private fun line(text: String): String {
        val clean = StorageFileView.printable(text).replace('\n', '�').replace('\t', ' ')
        return if (clean.length > MAX_LINE_CHARS) clean.take(MAX_LINE_CHARS) + "…" else clean
    }

    private const val HEX_DIGITS = "0123456789ABCDEFabcdef"
    private const val HEX_RADIX = 16
    private const val TABLES_SQL =
        "SELECT hex(name) FROM sqlite_master WHERE type = 'table' AND substr(name, 1, 7) <> 'sqlite_' ORDER BY name;"
}
