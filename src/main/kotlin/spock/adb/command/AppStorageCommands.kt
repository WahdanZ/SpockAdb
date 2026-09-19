package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellQuote
import spock.adb.getDefaultActivityForApplication
import spock.adb.isAppInstall
import spock.adb.startActivity
import spock.adb.storage.AppStoragePaths
import spock.adb.storage.StorageFile
import spock.adb.storage.StorageKind
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

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
     *
     * A name holding a newline is skipped on the device. The output is split into lines, so the
     * app could otherwise name one file to be listed as several, including ones that do not exist.
     */
    fun listCommand(packageName: String): String = RunAs.command(
        packageName,
        "for f in ${AppStoragePaths.SHARED_PREFS_DIR}/* ${AppStoragePaths.DATASTORE_DIR}/*; " +
            "do case \"\$f\" in *'\n'*) continue;; esac; [ -f \"\$f\" ] && echo \"\$f\"; done; echo rc=0",
    )

    /**
     * One directory's entries, each tagged `d` or `f`, so nothing has to parse `ls`.
     *
     * File names contain spaces on real devices — `shared_prefs/ spaced name .xml` is in the
     * test app — and `ls -la` puts the name last among columns whose count varies by toybox
     * version. A marker and the path is unambiguous: the path is the rest of the line. A name
     * holding a newline is skipped, as the preferences listing already skips it, because
     * nothing downstream could tell where such a line ended.
     */
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
     *
     * The mode is set before the move, so the file never appears with any other. `cat >` creates
     * it under the shell's umask, which on a device is nothing at all: without this the file is
     * left world-readable and world-writable until the app happens to rewrite it itself.
     */
    fun writeCommand(packageName: String, staged: String, file: StorageFile, size: Int): String {
        val target = ShellQuote.quote(file.path)
        val incoming = ShellQuote.quote(file.path + INCOMING_SUFFIX)
        val script = buildString {
            append("cat > $incoming && [ \$(wc -c < $incoming) -eq $size ]")
            if (file.kind == StorageKind.SHARED_PREFERENCES) append(" && rm -f ${ShellQuote.quote(file.path + ".bak")}")
            append(" && chmod ${modeOf(file.kind)} $incoming")
            append(" && mv $incoming $target; status=\$?; rm -f $incoming; echo rc=\$status")
        }
        return "cat ${ShellQuote.quote(staged)} | ${RunAs.command(packageName, script)}"
    }

    /** What Android itself creates the file as, so an edited file is indistinguishable from a written one. */
    private fun modeOf(kind: StorageKind): String = when (kind) {
        // SharedPreferencesImpl sets rw-rw---- on the file it writes.
        StorageKind.SHARED_PREFERENCES -> "660"
        // DataStore writes through a plain file stream, which leaves rw-------.
        else -> "600"
    }

    /**
     * The staged copy holds app state, and `adb push` may leave it readable by every app on the
     * device. The status is echoed because a `chmod` that failed must stop the write, not be
     * written around: the alternative is app data left world-readable in a shared directory.
     */
    fun restrictStagedCommand(staged: String): String = "chmod 600 ${ShellQuote.quote(staged)}; echo rc=\$?"

    fun removeStagedCommand(staged: String): String = "rm -f ${ShellQuote.quote(staged)}; echo rc=\$?"

    /** Said when the staged copy is still on the device, so the developer can remove it. */
    fun stagedLeftOverMessage(staged: String): String =
        "The copy staged at $staged could not be removed from the device. It holds the bytes that " +
            "were written; remove it with: adb shell rm -f $staged"

    /**
     * Stopping the app, with a status of its own.
     *
     * Not through `run-as`: force-stopping is the shell user's to do, and it is the step the
     * whole write sequence rests on — an app still running writes its preferences back from
     * memory over the edit.
     */
    fun stopCommand(packageName: String): String = "am force-stop ${ShellQuote.quote(packageName)}; echo rc=\$?"

    /**
     * Why the app was not stopped, or null when it was.
     *
     * `am` reports a permission or service error on its output and can still exit 0, so what it
     * said is read as well as the status.
     */
    fun stopFailure(packageName: String, outcome: RunAsOutcome): String? {
        val said = when (outcome) {
            is RunAsOutcome.Succeeded -> outcome.lines.filter(::isComplaint).joinToString("; ")
            is RunAsOutcome.NotDebuggable -> outcome.said
            is RunAsOutcome.Unreachable -> outcome.said
            is RunAsOutcome.Failed -> outcome.said.ifEmpty {
                "the device exited with ${outcome.status ?: "no status at all"}"
            }
        }
        if (said.isEmpty()) return null
        return "'$packageName' could not be stopped, so nothing was written: $said"
    }

    private fun isComplaint(line: String): Boolean =
        COMPLAINTS.any { line.contains(it, ignoreCase = true) }

    private val COMPLAINTS = listOf("error", "exception", "denied", "not permitted")

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

internal const val STORAGE_TIMEOUT_SECONDS = 20L

/** The result of a write, carrying what the file held before so the write can be undone. */
class AppStorageWrite(
    val file: StorageFile,
    val previous: ByteArray,
    /** False when a restart was not asked for, or the app has no launchable activity. */
    val restarted: Boolean,
    /** What the developer should know besides the write itself — a staged copy left behind. */
    val warning: String? = null,
)

/**
 * The file on the device is no longer the one the edit was made against, so nothing was written.
 *
 * Its own type rather than a plain [IllegalStateException] so the editor can say so where the
 * developer is editing — the message alone is one line of status among many, and the answer to
 * it is a specific one: read the file again before applying.
 */
class AppStorageChangedException(message: String) : IllegalStateException(message)

/**
 * The file was replaced, but reading it back failed or showed other bytes than were sent.
 *
 * Distinct from a failed write because the old content is gone either way: [previous] is what the
 * file held before, so the caller can still offer to put it back.
 */
class AppStorageUnverifiedWriteException(
    message: String,
    val previous: ByteArray,
    /**
     * What the file came back as, or null when it could not be read at all. Undoing a write
     * means writing [previous] over what is there now, so without this there is nothing to
     * check the file against and undo cannot be offered honestly.
     */
    val written: ByteArray? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** SharedPreferences and DataStore files of [packageName], sorted by path. */
internal fun IDevice.listAppStorage(packageName: String): List<StorageFile> {
    ShellQuote.requireValidComponent(packageName, "Package name")
    return when (val outcome = RunAs.classify(runAsShell(AppStorageShell.listCommand(packageName)))) {
        is RunAsOutcome.Succeeded -> outcome.lines.mapNotNull(AppStoragePaths::classify).sortedBy { it.path }
        else -> error(AppStorageShell.failureMessage(packageName, "list the storage", outcome))
    }
}

/** @throws IllegalStateException carrying what the device said, including when the file does not exist. */
internal fun IDevice.readAppStorageFile(packageName: String, file: StorageFile): ByteArray =
    readAppStorageBytes(packageName, file.path)

/**
 * Any file inside the app's data directory, by path.
 *
 * Reading is not writing: the tree browses a database or a cached blob, and a write still takes
 * a [StorageFile], which only [AppStoragePaths.classify] produces. The size cap is the same one
 * the preferences read has, so a large database is refused rather than pulled into memory.
 */
internal fun IDevice.readAppStorageBytes(packageName: String, path: String): ByteArray {
    ShellQuote.requireValidComponent(packageName, "Package name")
    AppStoragePaths.requireBrowsable(path)
    val outcome = RunAs.classify(runAsShell(AppStorageShell.readCommand(packageName, path)))
    val lines = (outcome as? RunAsOutcome.Succeeded)?.lines
        ?: error(AppStorageShell.failureMessage(packageName, "read $path", outcome))
    return try {
        Base64.getDecoder().decode(lines.joinToString(""))
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException("$path did not come back as base64: ${lines.take(2).joinToString(" ")}", e)
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
    stopApp(packageName)
    val previous = readAppStorageFile(packageName, file)
    if (expected != null) checkUnchanged(file, previous, expected)

    val leftOver = staged(content) { remote ->
        val outcome = RunAs.classify(runAsShell(AppStorageShell.writeCommand(packageName, remote, file, content.size)))
        check(outcome is RunAsOutcome.Succeeded) {
            AppStorageShell.failureMessage(packageName, "write ${file.path}", outcome)
        }
    }

    verifyWritten(packageName, file, content, previous)
    val restarted = restart && runCatching { relaunch(packageName) }.getOrDefault(false)
    return AppStorageWrite(file, previous, restarted, leftOver?.let(AppStorageShell::stagedLeftOverMessage))
}

/** @throws IllegalStateException when the app is still running, before anything has been staged. */
private fun IDevice.stopApp(packageName: String) {
    val outcome = RunAs.classify(runAsShell(AppStorageShell.stopCommand(packageName)))
    AppStorageShell.stopFailure(packageName, outcome)?.let { error(it) }
}

private fun checkUnchanged(file: StorageFile, current: ByteArray, expected: ByteArray) {
    if (current.contentEquals(expected)) return
    throw AppStorageChangedException(
        "${file.path} changed on the device after it was read, so nothing was written. Reload it and " +
            "make the change again.",
    )
}

/** @throws AppStorageUnverifiedWriteException carrying [previous], as the file has already been replaced. */
private fun IDevice.verifyWritten(packageName: String, file: StorageFile, content: ByteArray, previous: ByteArray) {
    val written = runCatching { readAppStorageFile(packageName, file) }.getOrElse {
        throw AppStorageUnverifiedWriteException(
            "${file.path} was replaced, but reading it back failed, so the write could not be verified: " +
                "${it.message}",
            previous,
            written = null,
            cause = it,
        )
    }
    if (!written.contentEquals(content)) {
        throw AppStorageUnverifiedWriteException(
            "${file.path} was replaced, but the device now holds ${written.size} bytes that differ from the " +
                "${content.size} sent, so the write could not be verified. Read it again to see what it holds.",
            previous,
            written = written,
        )
    }
}

/**
 * Pushes [content] to a fresh path under [AppStorageShell.TEMP_DIR], and removes it again
 * whatever [use] does.
 *
 * @return the staged path when the device would not remove it, so the caller can say so; null
 *   when it is gone, which is the ordinary answer.
 */
private fun IDevice.staged(content: ByteArray, use: (String) -> Unit): String? {
    val remote = "${AppStorageShell.TEMP_DIR}/spock-storage-${UUID.randomUUID()}"
    // createTempFile makes the file readable by its owner only, which matters: it holds app state.
    val local = Files.createTempFile("spock-storage", ".bin")
    val failure = runCatching {
        Files.write(local, content)
        pushFile(local.toString(), remote)
        val restricted = RunAs.classify(runAsShell(AppStorageShell.restrictStagedCommand(remote)))
        check(restricted is RunAsOutcome.Succeeded) {
            "The copy staged at $remote could not be made unreadable to other apps, so nothing was " +
                "written. Remove it with: adb shell rm -f $remote"
        }
        use(remote)
    }.exceptionOrNull()
    val leftOver = cleanUpStaged(remote)
    Files.deleteIfExists(local)

    if (failure == null) return leftOver
    // A copy of the app's data left in a shared directory is worth saying even while reporting
    // what went wrong, so it is added to the failure rather than kept for a caller that is
    // never reached.
    throw if (leftOver == null) {
        failure
    } else {
        IllegalStateException("${failure.message} ${AppStorageShell.stagedLeftOverMessage(leftOver)}", failure)
    }
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

/** The staged path when the device would not remove it; null when it is gone. */
private fun IDevice.cleanUpStaged(remote: String): String? {
    val outcome = runCatching { RunAs.classify(runAsShell(AppStorageShell.removeStagedCommand(remote))) }.getOrNull()
    return if (outcome is RunAsOutcome.Succeeded) null else remote
}

private fun IDevice.relaunch(packageName: String): Boolean {
    val activity = getDefaultActivityForApplication(packageName).trim()
    if (activity.isEmpty()) return false
    startActivity(activity)
    return true
}
