package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import spock.adb.ShellQuote
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Where on the device an agent may read and write.
 *
 * Deliberately stricter than `adb` itself. `adb pull` will happily hand over anything the
 * shell user can read, and an agent that can be talked into pulling `/data/data/<someone
 * else>/databases` is an exfiltration path wearing a debugging tool's clothes. The three
 * prefixes below are where developer artefacts actually live — screen recordings, exported
 * databases, test fixtures — and `android_run_adb_command` remains as the escape hatch for
 * anything else, where it is gated behind a per-call confirmation.
 */
internal object DevicePaths {

    private val ALLOWED_PREFIXES = listOf("/sdcard/", "/storage/", "/data/local/tmp/")

    /** @throws IllegalArgumentException with a message naming what *is* allowed. */
    fun validate(path: String): String {
        require(path.startsWith("/")) {
            "'$path' must be an absolute device path, starting with '/'."
        }
        require(".." !in path.split("/")) {
            "'$path' must not contain '..'. Give the full path instead."
        }
        require(ALLOWED_PREFIXES.any { path.startsWith(it) }) {
            "'$path' is outside the paths this tool may touch (" +
                ALLOWED_PREFIXES.joinToString() + "). This is deliberately stricter than adb: " +
                "other apps' private storage is not a debugging artefact. If you genuinely need " +
                "it, use android_run_adb_command, which asks the developer first."
        }
        return path
    }
}

/**
 * Which files on the *developer's machine* an agent may read.
 *
 * [DevicePaths] guards the device side, and this is its mirror. Without it the two transfer
 * tools compose into an arbitrary local read: push `~/.ssh/id_rsa` to `/sdcard`, pull it back,
 * and a file the agent could never open directly arrives in its context — neither call asking
 * the developer anything, because neither is destructive on its own.
 *
 * `PullFileTool` already refuses a caller-chosen *destination* for exactly this reason. Leaving
 * the source unrestricted granted the read half of the same power the write half was denied.
 */
internal object LocalPaths {

    /**
     * @param roots directories the file may live under. Resolved to real paths before
     *   comparison, so a symlink or a `..` inside an allowed root cannot point outside it —
     *   a textual prefix check is not enough here, unlike on the device where paths are opaque.
     * @throws IllegalArgumentException naming the directories that are allowed.
     */
    fun validate(path: Path, roots: List<Path>): Path {
        val real = runCatching { path.toRealPath() }.getOrElse {
            throw IllegalArgumentException("'$path' could not be resolved on this machine.")
        }
        val realRoots = roots.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
        require(realRoots.any { real.startsWith(it) }) {
            "'$path' is outside the directories this tool may read (" +
                (realRoots.joinToString().ifBlank { "none are available" }) + "). Reading any " +
                "file on the developer's machine is not a debugging operation: pushed to a " +
                "device it can be pulled straight back, so this would hand over private keys " +
                "and credentials as readily as a test fixture. Move the file into one of those " +
                "directories, or use android_run_adb_command, which asks the developer first."
        }
        return real
    }
}

/** Files pulled off a device land here, never at a path the caller chose. */
internal fun defaultPullDirectory(): Path = runCatching {
    Path.of(PathManager.getConfigPath(), "spock-adb", "pulls")
}.getOrElse { Path.of(System.getProperty("java.io.tmpdir"), "spock-adb-pulls") }

/** Shared cap. Big enough for a database or a three-minute recording, small enough to notice. */
internal const val MAX_TRANSFER_BYTES = 50L * 1024 * 1024

/**
 * `android_push_file` — put a local file onto the device.
 *
 * @param transferDirectory injectable for the same reason [PullFileTool] takes one: the
 *   allowed source roots are part of what this tool refuses to do, and a test that cannot
 *   choose them cannot check the refusal.
 */
class PushFileTool(
    private val transferDirectory: () -> Path = ::defaultPullDirectory,
) : AdbTool {

    override val name = "android_push_file"

    override val description =
        "Copy a file from this machine onto the device, for example a test fixture, a " +
            "seeded database or a config file. The destination must be under /sdcard, " +
            "/storage or /data/local/tmp, and the source must be inside the open project or " +
            "the IDE's Spock ADB pull directory."

    override val safety = ToolSafety.SAFE_ACTION

    override val inputSchema: JsonObject = Schema.obj {
        string(
            "localPath",
            "Absolute path of the file on this machine, inside the open project or the " +
                "IDE's Spock ADB pull directory.",
            required = true,
        )
        string(
            "remotePath",
            "Absolute destination on the device, under /sdcard, /storage or /data/local/tmp.",
            required = true,
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val remotePath = DevicePaths.validate(arguments.requiredString("remotePath"))
        val given = Path.of(arguments.requiredString("localPath"))

        if (!Files.isRegularFile(given)) {
            return ToolResult.error("'$given' is not a file on this machine.")
        }
        val local = LocalPaths.validate(given, allowedSourceRoots(context))
        val size = Files.size(local)
        if (size > MAX_TRANSFER_BYTES) {
            return ToolResult.error(
                "'$local' is $size bytes, over the ${MAX_TRANSFER_BYTES}-byte transfer limit.",
            )
        }

        device.pushFile(local.toAbsolutePath().toString(), remotePath)
        return ToolResult.text("Pushed $size bytes to $remotePath.")
    }

    /**
     * The open project, and the directory pulls land in.
     *
     * The project is where fixtures actually live, and the pull directory makes
     * pull-edit-push work and doubles as the drop-box for anything else. Both are places the
     * developer has already put material in front of the IDE; the rest of the filesystem has
     * not been.
     */
    private fun allowedSourceRoots(context: ToolContext): List<Path> = listOfNotNull(
        runCatching { transferDirectory() }.getOrNull(),
        context.project?.basePath?.let { runCatching { Path.of(it) }.getOrNull() },
    )
}

/**
 * `android_pull_file` — bring a file off the device.
 *
 * The local destination is **not** a parameter. A tool that writes to a path its caller chose
 * lets anything holding the MCP token drop a file anywhere on the developer's filesystem, and
 * no debugging workflow needs that; pulls land in one known directory and the tool reports
 * where.
 */
class PullFileTool(
    private val destinationDirectory: () -> Path = ::defaultPullDirectory,
) : AdbTool {

    override val name = "android_pull_file"

    override val description =
        "Copy a file off the device — a screen recording, an exported database, a log — into " +
            "the IDE's own pull directory, and report where it landed. The source must be " +
            "under /sdcard, /storage or /data/local/tmp. Small text files are also returned " +
            "inline so you can read them without a second call."

    override val safety = ToolSafety.SAFE_ACTION

    override val inputSchema: JsonObject = Schema.obj {
        string(
            "remotePath",
            "Absolute path on the device, under /sdcard, /storage or /data/local/tmp.",
            required = true,
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val remotePath = DevicePaths.validate(arguments.requiredString("remotePath"))

        val local = pull(device, remotePath)
        val size = Files.size(local)

        return ToolResult.text(
            buildString {
                append("Pulled ").append(size).append(" bytes from ").append(remotePath)
                append(" to ").append(local).append('.')
                inlineTextOf(local, size)?.let { append("\n\nContents:\n").append(it) }
            },
        )
    }

    /** @throws IllegalStateException when the device produced nothing, which ddmlib reports as success. */
    private fun pull(device: IDevice, remotePath: String): Path {
        val directory = destinationDirectory()
        Files.createDirectories(directory)

        // Pull to a temporary neighbour and move into place, so a failed transfer cannot leave
        // a half-written file behind under the name of a previous good one.
        val destination = directory.resolve(localNameFor(remotePath))
        // Resolved, not created: createTempFile would make the file itself, and then the
        // "did the device actually send anything" check below could never fail.
        val staging = directory.resolve("pull-" + java.util.UUID.randomUUID() + ".part")
        // Asked before transferring, so an oversized file is refused rather than written and
        // then deleted. Best effort: a device without `stat` leaves this null, and the check
        // after the transfer is what actually guarantees the cap.
        remoteSizeOf(device, remotePath)?.let { remote -> requireWithinCap(remotePath, remote) }

        try {
            device.pullFile(remotePath, staging.toString())
            check(Files.exists(staging)) { "The device returned no file for '$remotePath'." }
            requireWithinCap(remotePath, Files.size(staging))
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Also removes an oversized transfer: the check above throws while staging still
            // holds it, and nothing over the cap is kept.
            Files.deleteIfExists(staging)
        }
        return destination
    }

    private fun requireWithinCap(remotePath: String, size: Long) {
        require(size <= MAX_TRANSFER_BYTES) {
            "'$remotePath' is $size bytes, over the ${MAX_TRANSFER_BYTES}-byte transfer limit. " +
                "Narrow it on the device first — for a recording, a shorter --time-limit."
        }
    }

    /** The device's own answer, or null when it cannot give one. */
    private fun remoteSizeOf(device: IDevice, remotePath: String): Long? = runCatching {
        McpShell.run(device, "stat -c %s " + ShellQuote.quote(remotePath)).trim().toLong()
    }.getOrNull()

    /** Keeps the remote basename when it is safe, so a pulled file is recognisable. */
    private fun localNameFor(remotePath: String): String {
        val base = remotePath.trimEnd('/').substringAfterLast('/')
        val safe = base.filter { it.isLetterOrDigit() || it in "._-" }
        return safe.ifBlank { "pulled-file" }
    }

    /**
     * The file's text, when it is small and actually text.
     *
     * Strict decoding rather than a byte sniff: a `.db` whose first bytes happen to be ASCII
     * would otherwise come back as mojibake that an agent would try to reason about.
     */
    private fun inlineTextOf(local: Path, size: Long): String? {
        if (size > MAX_INLINE_BYTES) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes = Files.readAllBytes(local)
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private companion object {
        const val MAX_INLINE_BYTES = 64L * 1024
    }
}
