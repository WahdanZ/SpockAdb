package spock.adb.mcp

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import spock.adb.mcp.tools.ToolSafety
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The activity history, on disk.
 *
 * In-memory history is lost on the restart that most often prompts the question it answers:
 * "what did that agent do to my device?" Destructive calls already reach `idea.log` for this
 * reason; this keeps the rest, in the same trust boundary and under the same cap.
 *
 * Newline-delimited JSON, appended a line at a time. An append cannot corrupt what is already
 * written, a half-written trailing line costs one record rather than the file, and it needs no
 * schema migration when a field is added — an unreadable line is skipped rather than fatal.
 *
 * Free of IntelliJ types so the round trip, the cap and the tolerance for a truncated file can
 * be tested directly rather than only inside a running IDE.
 */
class McpHistoryStore(
    private val file: Path,
    capacity: Int,
    /**
     * Reported rather than swallowed. A history that has quietly stopped persisting looks
     * exactly like one with nothing to say, which is the one moment it matters that the two
     * are told apart, so the caller is given the failure to log.
     */
    private val onError: (String, IOException) -> Unit = { _, _ -> },
) {

    var capacity: Int = capacity.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    private val gson = Gson()

    /**
     * Appends one call.
     *
     * @return false when it could not be written. A history that cannot be persisted must not
     *   take the tool call down with it — the call already happened.
     */
    fun append(calls: List<McpCall>): Boolean {
        if (calls.isEmpty()) return true
        return try {
            file.parent?.let { Files.createDirectories(it) }
            val lines = calls.joinToString("") { gson.toJson(Record.of(it)) + "\n" }
            Files.write(
                file,
                lines.toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
            true
        } catch (e: IOException) {
            onError("could not append to the MCP history file", e)
            false
        }
    }

    /** Oldest first, capped to the newest [capacity]. Missing or unreadable file means empty. */
    fun load(): List<McpCall> {
        if (!Files.exists(file)) return emptyList()
        val lines = try {
            Files.readAllLines(file, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            onError("could not read the MCP history file", e)
            return emptyList()
        }
        return lines.mapNotNull(::parse).takeLast(capacity)
    }

    /**
     * Rewrites the file to the newest [capacity] records.
     *
     * Appending forever is what makes appending cheap, so the trim is a separate, occasional
     * step rather than something every write pays for. Written to a neighbour and moved into
     * place, so an interrupted compaction cannot lose the history it was tidying.
     */
    fun compact(): Boolean {
        val kept = load()
        return try {
            val staging = file.resolveSibling(file.fileName.toString() + ".compacting")
            Files.write(
                staging,
                kept.joinToString("") { gson.toJson(Record.of(it)) + "\n" }
                    .toByteArray(StandardCharsets.UTF_8),
            )
            Files.move(staging, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            onError("could not compact the MCP history file", e)
            false
        }
    }

    /** True once the file holds meaningfully more than the cap, so trimming is worth doing. */
    fun needsCompaction(): Boolean {
        if (!Files.exists(file)) return false
        val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8).size }.getOrDefault(0)
        return lines > capacity * COMPACT_SLACK
    }

    fun clear(): Boolean = try {
        Files.deleteIfExists(file)
        true
    } catch (e: IOException) {
        onError("could not delete the MCP history file", e)
        false
    }

    /**
     * A line, or null when it cannot be read.
     *
     * Tolerant on purpose: a truncated last line after a crash, or a record written by a newer
     * version, costs that one entry rather than the whole history.
     */
    private fun parse(line: String): McpCall? {
        if (line.isBlank()) return null
        val record = try {
            gson.fromJson(line, Record::class.java)
        } catch (ignored: JsonSyntaxException) {
            // Named rather than reported: a line this cannot read is a case the format is
            // designed for, not a fault, and a corrupt file would otherwise log once per line.
            return null
        } ?: return null
        return record.toCall()
    }

    /**
     * The on-disk shape, kept separate from [McpCall].
     *
     * [McpCall] is free to gain computed members and change constructor defaults; a stored
     * record cannot, because files written by an older version have to keep loading.
     */
    private data class Record(
        val toolName: String? = null,
        val safety: String? = null,
        val arguments: String? = null,
        val result: String? = null,
        val durationMs: Long = 0,
        val isError: Boolean = false,
        val client: String? = null,
        val deviceSerial: String? = null,
        val timestamp: Long = 0,
    ) {
        fun toCall(): McpCall? {
            val name = toolName?.takeIf { it.isNotBlank() } ?: return null
            val level = safety?.let { value ->
                ToolSafety.entries.firstOrNull { it.name == value }
            } ?: return null
            return McpCall(
                toolName = name,
                safety = level,
                arguments = arguments.orEmpty(),
                result = result.orEmpty(),
                durationMs = durationMs,
                isError = isError,
                client = client,
                deviceSerial = deviceSerial,
                timestamp = timestamp,
            )
        }

        companion object {
            fun of(call: McpCall) = Record(
                toolName = call.toolName,
                safety = call.safety.name,
                arguments = call.arguments,
                result = call.result,
                durationMs = call.durationMs,
                isError = call.isError,
                client = call.client,
                deviceSerial = call.deviceSerial,
                timestamp = call.timestamp,
            )
        }
    }

    private companion object {
        /** Let the file drift to twice the cap before rewriting it, so trims stay rare. */
        const val COMPACT_SLACK = 2
    }
}
