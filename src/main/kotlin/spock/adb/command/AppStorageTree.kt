package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.StorageEntry
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

class ReadAppFileCommand : Command<AppFileRequest, ByteArray> {
    override fun execute(p: AppFileRequest, project: Project, device: IDevice): ByteArray =
        device.readAppStorageBytes(p.packageName, p.path)
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
