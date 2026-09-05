package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import spock.adb.mcp.tools.ToolSafety
import java.nio.file.Files
import java.nio.file.Path

/**
 * The asynchronous half of the history.
 *
 * Every case ends at `shutdown()`, which is both the assertion point — it is the one moment the
 * queue is guaranteed to be on disk — and what stops the test leaving a thread behind.
 */
class McpHistoryWriterTest {

    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("history.ndjson")

    private fun call(at: Long) = McpCall(
        toolName = "android_list_devices",
        safety = ToolSafety.READ_ONLY,
        arguments = "{}",
        result = "one device",
        durationMs = 1,
        isError = false,
        client = "Claude Code",
        deviceSerial = "emulator-5554",
        timestamp = at,
    )

    private fun writer(capacity: Int = 100): Pair<McpHistoryWriter, McpHistoryStore> {
        val store = McpHistoryStore(file, capacity)
        return McpHistoryWriter(store) to store
    }

    @Test
    fun `recorded calls reach the file`() {
        val (writer, store) = writer()

        (1L..3L).forEach { writer.record(call(it)) }
        writer.shutdown()

        assertEquals(listOf(1L, 2L, 3L), store.load().map { it.timestamp })
    }

    @Test
    fun `shutdown writes what is still queued`() {
        // The calls made in the last moments before the IDE closes are exactly the ones worth
        // having, so a shutdown that dropped the queue would fail at the only time it matters.
        val (writer, store) = writer()

        writer.record(call(1))
        writer.shutdown()

        assertEquals(1, store.load().size)
    }

    @Test
    fun `an IDE that records nothing never creates the file`() {
        val (writer, _) = writer()

        writer.shutdown()

        assertFalse(Files.exists(file), "the history file should not exist")
    }

    @Test
    fun `the file is trimmed once it drifts past the cap`() {
        val (writer, store) = writer(capacity = 3)

        (1L..20L).forEach { writer.record(call(it)) }
        writer.shutdown()

        assertTrue(Files.readAllLines(file).size <= 3 * 2, Files.readAllLines(file).size.toString())
        assertEquals(listOf(18L, 19L, 20L), store.load().map { it.timestamp })
    }

    @Test
    fun `clearing removes the file and drops anything still queued`() {
        val (writer, store) = writer()
        writer.record(call(1))

        writer.clear()
        writer.shutdown()

        assertEquals(emptyList<McpCall>(), store.load())
    }

    @Test
    fun `recording after shutdown is ignored rather than thrown`() {
        // Reached when a call lands as the IDE is closing. The tool call has already happened;
        // failing it to report that its record could not be filed would be the wrong trade.
        val (writer, _) = writer()
        writer.shutdown()

        writer.record(call(1))
    }
}
