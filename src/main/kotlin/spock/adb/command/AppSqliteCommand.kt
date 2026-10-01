package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellQuote
import spock.adb.storage.AppStoragePaths

/** A SQLite database in an app's data directory, for the storage tree's read-only view. */
class AppSqliteRequest(val packageName: String, val path: String)

/**
 * The tables of a SQLite database, each with its row count and first rows, as text.
 *
 * Read with the device's own `sqlite3` as the app, through `run-as`, opened `-readonly` so a
 * running app's writes and locks are untouched. Many physical devices ship without `sqlite3`;
 * then the view says so rather than failing.
 */
class AppSqliteCommand : Command<AppSqliteRequest, String> {

    override fun execute(p: AppSqliteRequest, project: Project, device: IDevice): String {
        ShellQuote.requireValidComponent(p.packageName, "Package name")
        AppStoragePaths.requireBrowsable(p.path)
        val listing = device.runAsShell(AppSqlite.tablesCommand(p.packageName, p.path))
        val tables = when (val outcome = RunAs.classify(listing)) {
            is RunAsOutcome.Succeeded -> outcome.lines.map { it.trim() }.filter { it.isNotEmpty() }
            is RunAsOutcome.Failed -> if (outcome.status == AppSqlite.NO_SQLITE) {
                return AppSqlite.NO_SQLITE_MESSAGE
            } else {
                error(AppStorageShell.failureMessage(p.packageName, "read ${p.path}", outcome))
            }
            else -> error(AppStorageShell.failureMessage(p.packageName, "read ${p.path}", outcome))
        }
        if (tables.isEmpty()) return "SQLite database with no tables."
        val shown = tables.take(AppSqlite.MAX_TABLES)
        val outcome = RunAs.classify(device.runAsShell(AppSqlite.rowsCommand(p.packageName, p.path, shown)))
        val lines = (outcome as? RunAsOutcome.Succeeded)?.lines
            ?: error(AppStorageShell.failureMessage(p.packageName, "read ${p.path}", outcome))
        return AppSqlite.render(tables, lines)
    }
}

internal object AppSqlite {

    const val NO_SQLITE = 127
    const val MAX_TABLES = 20
    const val MAX_ROWS = 20

    const val NO_SQLITE_MESSAGE =
        "SQLite database. This device has no sqlite3 command, so its tables cannot be read here. " +
            "Emulators have it; on a phone, pull the file and open it on your computer."

    fun tablesCommand(packageName: String, path: String): String = RunAs.command(
        packageName,
        "f=${ShellQuote.quote(path)}; " +
            "if ! command -v sqlite3 >/dev/null 2>&1; then echo rc=$NO_SQLITE; else " +
            "sqlite3 -readonly \"\$f\" ${ShellQuote.quote(TABLES_SQL)}; echo rc=\$?; fi",
    )

    /** One `sqlite3` per table, so one unreadable table cannot hide the rest. */
    fun rowsCommand(packageName: String, path: String, tables: List<String>): String {
        val perTable = tables.joinToString(" ") { table ->
            val name = "\"" + table.replace("\"", "\"\"") + "\""
            val sql = "SELECT count(*) AS rows FROM $name; SELECT * FROM $name LIMIT $MAX_ROWS;"
            "echo ${ShellQuote.quote("$TABLE_MARK$table")}; " +
                "sqlite3 -readonly -header -separator ' | ' \"\$f\" ${ShellQuote.quote(sql)} 2>&1;"
        }
        return RunAs.command(packageName, "f=${ShellQuote.quote(path)}; $perTable echo rc=0")
    }

    fun render(tables: List<String>, lines: List<String>): String = buildString {
        append("SQLite database · ${tables.size} table${if (tables.size == 1) "" else "s"}")
        if (tables.size > MAX_TABLES) append(" (first $MAX_TABLES shown)")
        append(" · first $MAX_ROWS rows of each, read-only\n")
        lines.forEach { line ->
            if (line.startsWith(TABLE_MARK)) {
                append("\n").append(line.removePrefix(TABLE_MARK)).append("\n")
            } else {
                append("  ").append(line).append("\n")
            }
        }
    }

    private const val TABLE_MARK = "== "
    private const val TABLES_SQL =
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name;"
}
