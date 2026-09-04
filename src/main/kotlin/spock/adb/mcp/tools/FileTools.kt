package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
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

/** Files pulled off a device land here, never at a path the caller chose. */
internal fun defaultPullDirectory(): Path = runCatching {
    Path.of(PathManager.getConfigPath(), "spock-adb", "pulls")
}.getOrElse { Path.of(System.getProperty("java.io.tmpdir"), "spock-adb-pulls") }

/** Shared cap. Big enough for a database or a three-minute recording, small enough to notice. */
internal const val MAX_TRANSFER_BYTES = 50L * 1024 * 1024

/** `android_push_file` — put a local file onto the device. */
class PushFileTool : AdbTool {

    override val name = "android_push_file"

    override val description =
        "Copy a file from this machine onto the device, for example a test fixture, a " +
            "seeded database or a config file. The destination must be under /sdcard, " +
            "/storage or /data/local/tmp."

    override val safety = ToolSafety.SAFE_ACTION

    override val inputSchema: JsonObject = Schema.obj {
        string("localPath", "Absolute path of the file on this machine.", required = true)
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
        val local = Path.of(arguments.requiredString("localPath"))

        if (!Files.isRegularFile(local)) {
            return ToolResult.error("'$local' is not a file on this machine.")
        }
        val size = Files.size(local)
        if (size > MAX_TRANSFER_BYTES) {
            return ToolResult.error(
                "'$local' is $size bytes, over the ${MAX_TRANSFER_BYTES}-byte transfer limit.",
            )
        }

        device.pushFile(local.toAbsolutePath().toString(), remotePath)
        return ToolResult.text("Pushed $size bytes to $remotePath.")
    }
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
        try {
            device.pullFile(remotePath, staging.toString())
            check(Files.exists(staging)) { "The device returned no file for '$remotePath'." }
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging)
        }
        return destination
    }

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
