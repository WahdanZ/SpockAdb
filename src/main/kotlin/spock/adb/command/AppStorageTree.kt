package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.StorageEntry
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Browsing the app's own data directory: what is in it, not what may be changed.
 *
 * Kept apart from the preferences commands because the boundary is different. Those accept only
 * a [spock.adb.storage.StorageFile], which is the editor's writable set; this lists whatever the
 * app has — databases, caches, files it wrote itself — and nothing here can be written, because
 * a write takes a `StorageFile` and the only way to obtain one is `AppStoragePaths.classify`.
 */
internal object StorageTree {

    /**
     * One directory's entries, each tagged `d` or `f`, so nothing has to parse `ls`.
     *
     * File names contain spaces on real devices — `shared_prefs/ spaced name .xml` is in the
     * test app — and `ls -la` puts the name last among columns whose count varies by toybox
     * version. A marker and the path is unambiguous: the path is the rest of the line. A name
     * holding a newline is skipped, as the preferences listing already skips it, because
     * nothing downstream could tell where such a line ended.
     */
    fun listDirectoryCommand(packageName: String, directory: String): String {
        val target = if (directory.isEmpty()) "." else directory
        return RunAs.command(
            packageName,
            "d=${ShellQuote.quote(target)}; " +
                "for f in \"\$d\"/* \"\$d\"/.*; do " +
                "case \"\$f\" in *'\n'*) continue;; */.|*/..) continue;; esac; " +
                "[ -e \"\$f\" ] || continue; " +
                "if [ -d \"\$f\" ]; then echo \"d \$f\"; else echo \"f \$f\"; fi; " +
                "done; echo rc=0",
        )
    }

    /**
     * Turns the tagged lines back into entries, dropping anything that is not one.
     *
     * The shell writes each path as it expanded it — already prefixed with the directory it was
     * given, or with `./` when that was the data directory itself — so the only normalising left
     * is dropping that dot.
     */
    fun parseEntries(lines: List<String>): List<StorageEntry> = lines
        .mapNotNull(::entryOf)
        .distinctBy { it.path }
        // Directories first, then files, each by name: the order a file tree is read in.
        .sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

    private fun entryOf(line: String): StorageEntry? {
        val isDirectory = when {
            line.startsWith(DIRECTORY_TAG) -> true
            line.startsWith(FILE_TAG) -> false
            else -> return null
        }
        val path = line.substring(DIRECTORY_TAG.length).removePrefix("./")
        if (!AppStoragePaths.isBrowsable(path) || path.isEmpty()) return null
        return StorageEntry(path, isDirectory)
    }

    /**
     * Reads a file for the tree's view, unless its first 16 bytes are SQLite's header.
     *
     * A database is read on the device with `sqlite3` instead, so it is never downloaded — and
     * the download's size cap would refuse most real ones anyway. The header is compared as
     * base64 because the shell cannot hold its closing NUL. Without `head` the check fails
     * quietly and the file is read as any other.
     */
    fun readFileCommand(packageName: String, path: String): String {
        val file = argument(path)
        return RunAs.command(
            packageName,
            "f=${ShellQuote.quote(file)}; " +
                "if [ -f \"\$f\" ] && [ \"\$(head -c 16 \"\$f\" 2>/dev/null | base64)\" = '$SQLITE_HEADER_BASE64' ]; " +
                "then echo rc=$STATUS_SQLITE; else ${AppStorageShell.readScript(file)}; fi",
        )
    }

    /** What [readFileCommand] reports for a SQLite database, clear of the read's own statuses. */
    const val STATUS_SQLITE = 92

    /** `SQLite format 3` and its NUL, as `base64` prints them. */
    private const val SQLITE_HEADER_BASE64 = "U1FMaXRlIGZvcm1hdCAzAA=="

    /**
     * [path] as an argument to a command that takes options. Paths are relative to the data
     * directory, so a file named `-x` at its top would otherwise be read as an option.
     */
    fun argument(path: String): String = if (path.startsWith("-")) "./$path" else path

    private const val DIRECTORY_TAG = "d "
    private const val FILE_TAG = "f "
}

/**
 * One directory of the app's data, as directories and files.
 *
 * Browsing only: what may be *edited* is still decided by [AppStoragePaths.classify], which a
 * write goes through and this does not.
 */
internal fun IDevice.listAppDirectory(packageName: String, directory: String): List<StorageEntry> {
    ShellQuote.requireValidComponent(packageName, "Package name")
    AppStoragePaths.requireBrowsable(directory)
    return when (val outcome = RunAs.classify(runAsShell(StorageTree.listDirectoryCommand(packageName, directory)))) {
        is RunAsOutcome.Succeeded -> StorageTree.parseEntries(outcome.lines)
        else -> error(AppStorageShell.failureMessage(packageName, "list $directory", outcome))
    }
}

/** Any file of an app's data, by path, for the tree's read-only view of it. */
class AppFileRequest(val packageName: String, val path: String)

/** What the tree's view got back for a file. */
sealed interface AppFileContent {

    /** The file itself, for the view to decode. */
    class Bytes(val bytes: ByteArray) : AppFileContent

    /** A SQLite database, recognised on the device and not downloaded: [AppSqliteCommand] reads it there. */
    object Sqlite : AppFileContent
}

class ReadAppFileCommand : Command<AppFileRequest, AppFileContent> {
    override fun execute(p: AppFileRequest, project: Project, device: IDevice): AppFileContent =
        device.readAppFile(p.packageName, p.path)
}

/** @throws IllegalStateException carrying what the device said, including when the file does not exist. */
internal fun IDevice.readAppFile(packageName: String, path: String): AppFileContent {
    ShellQuote.requireValidComponent(packageName, "Package name")
    AppStoragePaths.requireBrowsable(path)
    val outcome = RunAs.classify(runAsShell(StorageTree.readFileCommand(packageName, path)))
    if (outcome is RunAsOutcome.Failed && outcome.status == StorageTree.STATUS_SQLITE) return AppFileContent.Sqlite
    return AppFileContent.Bytes(readBase64(packageName, path, outcome))
}

/** The bytes [AppStorageShell.readScript] printed, or what went wrong instead. */
internal fun readBase64(packageName: String, path: String, outcome: RunAsOutcome): ByteArray {
    val lines = (outcome as? RunAsOutcome.Succeeded)?.lines
        ?: error(AppStorageShell.failureMessage(packageName, "read $path", outcome))
    return try {
        Base64.getDecoder().decode(lines.joinToString(""))
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("$path did not come back as base64: ${lines.take(2).joinToString(" ")}", e)
    }
}

/** One directory of an app's data, for the storage tree. */
class AppDirectoryRequest(val packageName: String, val directory: String)

class ListAppDirectoryCommand : Command<AppDirectoryRequest, List<StorageEntry>> {
    override fun execute(p: AppDirectoryRequest, project: Project, device: IDevice): List<StorageEntry> =
        device.listAppDirectory(p.packageName, p.directory)
}

internal fun IDevice.runAsShell(command: String): String {
    val receiver = ShellOutputReceiver()
    executeShellCommand(command, receiver, STORAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return receiver.toString()
}
