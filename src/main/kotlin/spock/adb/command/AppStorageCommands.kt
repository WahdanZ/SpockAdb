package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.forceKillApp
import spock.adb.getDefaultActivityForApplication
import spock.adb.isAppInstall
import spock.adb.startActivity
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.StorageFile
import spock.adb.storage.StorageKind
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The shell side of the app storage editor, kept free of [IDevice] so the exact commands that
 * reach a device are testable.
 *
 * Everything runs through [RunAs], with paths relative to the app's data directory, so the
 * editor needs a debuggable build and cannot touch anything outside that app's own sandbox.
 */
internal object AppStorageShell {

    const val TEMP_DIR = "/data/local/tmp"

    /** Larger than any preferences file anyone means to edit by hand, small enough to hold in memory. */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /** Statuses the read script reports for itself, clear of anything `base64` returns. */
    private const val STATUS_NOT_FOUND = 90
    private const val STATUS_TOO_LARGE = 91

    /** Where a write lands before it replaces the real file. */
    private const val INCOMING_SUFFIX = ".spock-incoming"

    /**
     * Regular files only, one per line. The status is literal rather than `$?`: the loop's own
     * status is that of its last `[ -f ]`, which is 1 whenever a directory is empty or missing.
     */
    fun listCommand(packageName: String): String = RunAs.command(
        packageName,
        "for f in ${AppStoragePaths.SHARED_PREFS_DIR}/* ${AppStoragePaths.DATASTORE_DIR}/*; " +
            "do [ -f \"\$f\" ] && echo \"\$f\"; done; echo rc=0",
    )

    /**
     * The file as base64, so XML and protobuf take the same byte-exact path through ddmlib's
     * text-only shell channel.
     */
    fun readCommand(packageName: String, path: String): String = RunAs.command(
        packageName,
        "f=${ShellQuote.quote(path)}; " +
            "if [ ! -f \"\$f\" ]; then echo \"\$f was not found\"; echo rc=$STATUS_NOT_FOUND; " +
            "elif [ \$(wc -c < \"\$f\") -gt $MAX_FILE_BYTES ]; then " +
            "echo \"\$f is larger than $MAX_FILE_BYTES bytes\"; echo rc=$STATUS_TOO_LARGE; " +
            "else base64 \"\$f\"; echo rc=\$?; fi",
    )

    /**
     * Replaces [file] with the bytes pushed to [staged].
     *
     * The shell user reads [staged] and pipes it into `run-as`, rather than the app reading
     * `/data/local/tmp` itself: whether an app's `run-as` context may read that directory varies
     * by Android version and SELinux policy, and the shell user always can.
     *
     * The new content goes to a neighbour first and is moved into place only once its size
     * matches. A failed or short copy — `cat` unable to read [staged] hands `run-as` an empty
     * stream — must never replace the file with nothing.
     *
     * For SharedPreferences the `.bak` beside the file goes too. On load, SharedPreferences treats
     * a leftover backup as the real file and restores it over ours, silently undoing the edit.
     */
    fun writeCommand(packageName: String, staged: String, file: StorageFile, size: Int): String {
        val target = ShellQuote.quote(file.path)
        val incoming = ShellQuote.quote(file.path + INCOMING_SUFFIX)
        val script = buildString {
            append("cat > $incoming && [ \$(wc -c < $incoming) -eq $size ]")
            if (file.kind == StorageKind.SHARED_PREFERENCES) append(" && rm -f ${ShellQuote.quote(file.path + ".bak")}")
            append(" && mv $incoming $target; status=\$?; rm -f $incoming; echo rc=\$status")
        }
        return "cat ${ShellQuote.quote(staged)} | ${RunAs.command(packageName, script)}"
    }

    fun removeStagedCommand(staged: String): String = "rm -f ${ShellQuote.quote(staged)}"

    /** A sentence for what went wrong, naming the package and what was being done to it. */
    fun failureMessage(packageName: String, doing: String, outcome: RunAsOutcome): String = when (outcome) {
        is RunAsOutcome.Succeeded -> "Could not $doing for '$packageName'."
        is RunAsOutcome.NotDebuggable ->
            "'$packageName' is not a debuggable build on this device, so its SharedPreferences and " +
                "DataStore files cannot be reached. Install the debug variant."
        is RunAsOutcome.Unreachable ->
            "run-as could not reach '$packageName' on this device (${outcome.said}). App storage " +
                "needs a debuggable build installed for the current user."
        is RunAsOutcome.Failed -> when {
            outcome.said.isNotEmpty() -> "Could not $doing for '$packageName': ${outcome.said}"
            outcome.status != null -> "Could not $doing for '$packageName': the device exited with ${outcome.status}."
            else ->
                "Could not $doing for '$packageName': the device reported no exit status, so run-as " +
                    "does not appear to be usable here."
        }
    }
}

private const val STORAGE_TIMEOUT_SECONDS = 20L

/** The result of a write, carrying what the file held before so the write can be undone. */
class AppStorageWrite(
    val file: StorageFile,
    val previous: ByteArray,
    /** False when a restart was not asked for, or the app has no launchable activity. */
    val restarted: Boolean,
)

/**
 * The file was replaced, but reading it back failed or showed other bytes than were sent.
 *
 * Distinct from a failed write because the old content is gone either way: [previous] is what the
 * file held before, so the caller can still offer to put it back.
 */
class AppStorageUnverifiedWriteException(
    message: String,
    val previous: ByteArray,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** SharedPreferences and DataStore files of [packageName], sorted by path. */
internal fun IDevice.listAppStorage(packageName: String): List<StorageFile> {
    ShellQuote.requireValidComponent(packageName, "Package name")
    return when (val outcome = RunAs.classify(shell(AppStorageShell.listCommand(packageName)))) {
        is RunAsOutcome.Succeeded -> outcome.lines.mapNotNull(AppStoragePaths::classify).sortedBy { it.path }
        else -> error(AppStorageShell.failureMessage(packageName, "list the storage", outcome))
    }
}

/** @throws IllegalStateException carrying what the device said, including when the file does not exist. */
internal fun IDevice.readAppStorageFile(packageName: String, file: StorageFile): ByteArray {
    ShellQuote.requireValidComponent(packageName, "Package name")
    val outcome = RunAs.classify(shell(AppStorageShell.readCommand(packageName, file.path)))
    val lines = (outcome as? RunAsOutcome.Succeeded)?.lines
        ?: error(AppStorageShell.failureMessage(packageName, "read ${file.path}", outcome))
    return try {
        Base64.getDecoder().decode(lines.joinToString(""))
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("${file.path} did not come back as base64: ${lines.take(2).joinToString(" ")}", e)
    }
}

/**
 * Replaces [file] with [content], for an app that is stopped first.
 *
 * In order:
 *  1. **Compare with [expected]** while the app still runs, and refuse when the file no longer
 *     matches — the app wrote it after the editor read it. A stale edit is refused before it
 *     costs the developer a running app.
 *  2. **Force-stop.** A running app holds its preferences in memory and writes them back on its
 *     next `apply()`, so without the stop the edit silently disappears.
 *  3. **Read the file again**, now nothing can change it, and compare once more: the app may
 *     have written between the first read and the stop.
 *  4. **Stage, copy, move** — see [AppStorageShell.writeCommand]. The staged copy is removed on
 *     every path, including failures.
 *  5. **Read it back** and compare. The device's word that a write succeeded is not the same as
 *     the file holding the bytes that were sent.
 *
 * Once the move has succeeded the write has landed, whatever follows: a restart that fails only
 * means [AppStorageWrite.restarted] is false.
 *
 * @param expected the bytes the edit was made against, or null to write whatever is there now.
 * @param restart launch the app again once the write is verified.
 * @throws AppStorageUnverifiedWriteException when the file was replaced but could not be read
 *   back as the bytes sent.
 * @throws IllegalStateException with a message naming what failed; nothing after the failing
 *   step has run.
 */
internal fun IDevice.writeAppStorageFile(
    packageName: String,
    file: StorageFile,
    content: ByteArray,
    expected: ByteArray?,
    restart: Boolean,
): AppStorageWrite {
    ShellQuote.requireValidComponent(packageName, "Package name")
    require(file.kind.format != null) { "${file.path} is a ${file.kind.label} file and cannot be written." }
    require(content.size <= AppStorageShell.MAX_FILE_BYTES) {
        "${content.size} bytes is more than the ${AppStorageShell.MAX_FILE_BYTES}-byte limit for a preferences file."
    }
    check(isAppInstall(packageName)) { "Package '$packageName' is not installed on this device." }

    if (expected != null) checkUnchanged(file, readAppStorageFile(packageName, file), expected)
    forceKillApp(packageName, STORAGE_TIMEOUT_SECONDS)
    val previous = readAppStorageFile(packageName, file)
    if (expected != null) checkUnchanged(file, previous, expected)

    staged(content) { remote ->
        val outcome = RunAs.classify(shell(AppStorageShell.writeCommand(packageName, remote, file, content.size)))
        check(outcome is RunAsOutcome.Succeeded) {
            AppStorageShell.failureMessage(packageName, "write ${file.path}", outcome)
        }
    }

    verifyWritten(packageName, file, content, previous)
    val restarted = restart && runCatching { relaunch(packageName) }.getOrDefault(false)
    return AppStorageWrite(file, previous, restarted)
}

private fun checkUnchanged(file: StorageFile, current: ByteArray, expected: ByteArray) {
    check(current.contentEquals(expected)) {
        "${file.path} changed on the device after it was read, so nothing was written. Reload it and " +
            "make the change again."
    }
}

/** @throws AppStorageUnverifiedWriteException carrying [previous], as the file has already been replaced. */
private fun IDevice.verifyWritten(packageName: String, file: StorageFile, content: ByteArray, previous: ByteArray) {
    val written = runCatching { readAppStorageFile(packageName, file) }.getOrElse {
        throw AppStorageUnverifiedWriteException(
            "${file.path} was replaced, but reading it back failed, so the write could not be verified: " +
                "${it.message}",
            previous,
            it,
        )
    }
    if (!written.contentEquals(content)) {
        throw AppStorageUnverifiedWriteException(
            "${file.path} was replaced, but the device now holds ${written.size} bytes that differ from the " +
                "${content.size} sent, so the write could not be verified. Read it again to see what it holds.",
            previous,
        )
    }
}

/** Pushes [content] to a fresh path under [AppStorageShell.TEMP_DIR], and removes it again whatever [use] does. */
private fun IDevice.staged(content: ByteArray, use: (String) -> Unit) {
    val remote = "${AppStorageShell.TEMP_DIR}/spock-storage-${UUID.randomUUID()}"
    // createTempFile makes the file readable by its owner only, which matters: it holds app state.
    val local = Files.createTempFile("spock-storage", ".bin")
    try {
        Files.write(local, content)
        pushFile(local.toString(), remote)
        use(remote)
    } finally {
        runCatching { shell(AppStorageShell.removeStagedCommand(remote)) }
        Files.deleteIfExists(local)
    }
}

private fun IDevice.relaunch(packageName: String): Boolean {
    val activity = getDefaultActivityForApplication(packageName).trim()
    if (activity.isEmpty()) return false
    startActivity(activity)
    return true
}

private fun IDevice.shell(command: String): String {
    val receiver = ShellOutputReceiver()
    executeShellCommand(command, receiver, STORAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return receiver.toString()
}

/**
 * The panel's entry points. The MCP tools call the same [IDevice] functions directly, as the
 * proxy tools do: they need no [Project], and cannot always supply one.
 */
class ListAppStorageCommand : Command<String, List<StorageFile>> {
    override fun execute(p: String, project: Project, device: IDevice): List<StorageFile> {
        check(device.isAppInstall(p)) { "Package '$p' is not installed on this device." }
        return device.listAppStorage(p)
    }
}

class AppStorageFileRequest(val packageName: String, val file: StorageFile)

class ReadAppStorageFileCommand : Command<AppStorageFileRequest, ByteArray> {
    override fun execute(p: AppStorageFileRequest, project: Project, device: IDevice): ByteArray =
        device.readAppStorageFile(p.packageName, p.file)
}

class AppStorageWriteRequest(
    val packageName: String,
    val file: StorageFile,
    val content: ByteArray,
    val expected: ByteArray?,
    val restart: Boolean,
)

class WriteAppStorageFileCommand : Command<AppStorageWriteRequest, AppStorageWrite> {
    override fun execute(p: AppStorageWriteRequest, project: Project, device: IDevice): AppStorageWrite =
        device.writeAppStorageFile(p.packageName, p.file, p.content, p.expected, p.restart)
}
