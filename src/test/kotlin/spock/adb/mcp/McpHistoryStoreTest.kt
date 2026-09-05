package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import spock.adb.mcp.tools.ToolSafety
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The history file is what answers "what did that agent do to my device" after the restart
 * that usually prompts the question, so the cases that matter are the ugly ones: a file
 * truncated mid-write, a record written by a version that knows a field this one does not,
 * and a directory the IDE cannot write to.
 */
class McpHistoryStoreTest {

    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("history.ndjson")

    private fun store(capacity: Int = 100) = McpHistoryStore(file, capacity)

    private fun call(
        name: String = "android_list_devices",
        safety: ToolSafety = ToolSafety.READ_ONLY,
        error: Boolean = false,
        at: Long = 1_000,
    ) = McpCall(
        toolName = name,
        safety = safety,
        arguments = """{"deviceSerial":"emulator-5554"}""",
        result = "one device",
        durationMs = 12,
        isError = error,
        client = "spock-assistant",
        deviceSerial = "emulator-5554",
        timestamp = at,
    )

    private fun appendRaw(line: String) =
        Files.write(file, line.toByteArray(), StandardOpenOption.APPEND)

    @Test
    fun `a call survives the round trip with every field intact`() {
        val original = call(name = "android_clear_app_data", safety = ToolSafety.DESTRUCTIVE, error = true)
        store().append(listOf(original))

        assertEquals(listOf(original), store().load())
    }

    @Test
    fun `appending adds to what is already there rather than replacing it`() {
        val store = store()
        store.append(listOf(call(at = 1)))
        store.append(listOf(call(at = 2)))

        assertEquals(listOf(1L, 2L), store.load().map { it.timestamp })
    }

    @Test
    fun `loading keeps the newest entries when the file holds more than the cap`() {
        store().append((1L..10L).map { call(at = it) })

        assertEquals(listOf(8L, 9L, 10L), store(capacity = 3).load().map { it.timestamp })
    }

    @Test
    fun `a truncated last line costs one record, not the history`() {
        val store = store()
        store.append((1L..3L).map { call(at = it) })
        // What a crash part-way through a write leaves behind.
        appendRaw("""{"toolName":"android_l""")

        assertEquals(listOf(1L, 2L, 3L), store.load().map { it.timestamp })
    }

    @Test
    fun `a record from a newer version is skipped rather than fatal`() {
        val store = store()
        store.append(listOf(call(at = 1)))
        appendRaw("""{"toolName":"x","safety":"SOMETHING_NEW","timestamp":2}""" + "\n")
        store.append(listOf(call(at = 3)))

        assertEquals(listOf(1L, 3L), store.load().map { it.timestamp })
    }

    @Test
    fun `compaction rewrites the file down to the cap`() {
        val store = store(capacity = 3)
        store.append((1L..10L).map { call(at = it) })
        assertTrue(store.needsCompaction())

        assertTrue(store.compact())

        assertEquals(3, Files.readAllLines(file).size)
        assertEquals(listOf(8L, 9L, 10L), store.load().map { it.timestamp })
        assertFalse(store.needsCompaction())
    }

    @Test
    fun `compaction leaves no staging file behind`() {
        val store = store(capacity = 2)
        store.append((1L..6L).map { call(at = it) })
        store.compact()

        val left = Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("history.ndjson"), left)
    }

    @Test
    fun `a missing file loads as empty rather than failing`() {
        assertEquals(emptyList<McpCall>(), store().load())
        assertFalse(store().needsCompaction())
    }

    @Test
    fun `a history that cannot be written does not take the tool call down with it`() {
        // The parent is a regular file, so creating the history under it can only fail.
        val blocker = Files.write(dir.resolve("blocker"), byteArrayOf(1))
        val store = McpHistoryStore(blocker.resolve("nested/history.ndjson"), 10)

        assertFalse(store.append(listOf(call())))
        assertEquals(emptyList<McpCall>(), store.load())
    }

    @Test
    fun `clearing removes the file`() {
        val store = store()
        store.append(listOf(call()))

        assertTrue(store.clear())
        assertEquals(emptyList<McpCall>(), store.load())
    }
}
