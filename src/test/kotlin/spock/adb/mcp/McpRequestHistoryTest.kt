package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolSafety

class McpRequestHistoryTest {

    private fun call(
        tool: String = "android_list_devices",
        safety: ToolSafety = ToolSafety.READ_ONLY,
        isError: Boolean = false,
        client: String? = "Claude Code",
        device: String? = "emulator-5554",
        arguments: String = "{}",
        result: String = "ok",
    ) = McpCall(tool, safety, arguments, result, 10, isError, client, device)

    @Test
    fun `newest calls come first`() {
        val history = McpRequestHistory()
        history.record(call(tool = "first"))
        history.record(call(tool = "second"))

        assertEquals(listOf("second", "first"), history.all().map { it.toolName })
    }

    @Test
    fun `history is bounded and drops the oldest`() {
        // An agent can issue hundreds of calls a minute; unbounded history is a leak.
        val history = McpRequestHistory(capacity = 2)
        listOf("a", "b", "c").forEach { history.record(call(tool = it)) }

        assertEquals(listOf("c", "b"), history.all().map { it.toolName })
        assertEquals(2, history.size())
    }

    @Test
    fun `lowering the capacity trims immediately`() {
        val history = McpRequestHistory(capacity = 100)
        repeat(10) { history.record(call(tool = "t$it")) }

        history.capacity = McpRequestHistory.MIN_CAPACITY
        assertTrue(history.size() <= McpRequestHistory.MIN_CAPACITY)
    }

    @Test
    fun `prepending puts the loaded history behind what is already there`() {
        // The persisted history loads on a background thread, so a call can be recorded while
        // the load is in flight. That call is the one the developer is watching for; replacing
        // the contents instead of prepending would throw it away.
        val history = McpRequestHistory()
        history.record(call(tool = "live"))

        history.prepend(listOf(call(tool = "older"), call(tool = "oldest")))

        assertEquals(listOf("live", "oldest", "older"), history.all().map { it.toolName })
    }

    @Test
    fun `prepending honours the current cap`() {
        // A file written when the cap was higher must not restore more than is allowed now.
        val history = McpRequestHistory(capacity = McpRequestHistory.MIN_CAPACITY)

        history.prepend((1..60).map { call(tool = "t$it") })

        assertEquals(McpRequestHistory.MIN_CAPACITY, history.size())
        assertEquals("t60", history.all().first().toolName)
    }

    @Test
    fun `capacity is clamped to a sane range`() {
        val history = McpRequestHistory()
        history.capacity = 1
        assertEquals(McpRequestHistory.MIN_CAPACITY, history.capacity)

        history.capacity = Int.MAX_VALUE
        assertEquals(McpRequestHistory.MAX_CAPACITY, history.capacity)
    }

    @Test
    fun `filters by tool, client, device and safety`() {
        val history = McpRequestHistory()
        history.record(call(tool = "android_get_logcat", client = "Cursor"))
        history.record(call(tool = "android_clear_app_data", safety = ToolSafety.DESTRUCTIVE, device = "abc123"))

        assertEquals(1, history.query(McpHistoryFilter(tool = "android_get_logcat")).size)
        assertEquals(1, history.query(McpHistoryFilter(client = "Cursor")).size)
        assertEquals(1, history.query(McpHistoryFilter(deviceSerial = "abc123")).size)
        assertEquals(1, history.query(McpHistoryFilter(safety = ToolSafety.DESTRUCTIVE)).size)
    }

    @Test
    fun `filters by outcome`() {
        val history = McpRequestHistory()
        history.record(call(tool = "ok"))
        history.record(call(tool = "bad", isError = true))

        val succeeded = history.query(McpHistoryFilter(outcome = McpHistoryFilter.Outcome.SUCCESS))
        val failed = history.query(McpHistoryFilter(outcome = McpHistoryFilter.Outcome.ERROR))

        assertEquals(listOf("ok"), succeeded.map { it.toolName })
        assertEquals(listOf("bad"), failed.map { it.toolName })
    }

    @Test
    fun `search covers tool name, arguments and result`() {
        val history = McpRequestHistory()
        history.record(call(tool = "android_open_deep_link", arguments = """{"uri":"myapp://checkout"}"""))
        history.record(call(tool = "android_list_devices", result = "emulator-5554"))

        assertEquals(1, history.query(McpHistoryFilter(query = "checkout")).size)
        assertEquals(1, history.query(McpHistoryFilter(query = "emulator")).size)
        assertEquals(1, history.query(McpHistoryFilter(query = "deep_link")).size)
    }

    @Test
    fun `a successful destructive call is recorded as confirmed`() {
        // Destructive tools cannot succeed without the developer approving, so success
        // implies confirmation; that is what the panel shows.
        assertTrue(call(safety = ToolSafety.DESTRUCTIVE, isError = false).wasConfirmed)
        assertTrue(!call(safety = ToolSafety.DESTRUCTIVE, isError = true).wasConfirmed)
        assertTrue(!call(safety = ToolSafety.READ_ONLY).wasConfirmed)
    }

    @Test
    fun `known tools and clients are distinct and sorted`() {
        val history = McpRequestHistory()
        history.record(call(tool = "b", client = "Cursor"))
        history.record(call(tool = "a", client = "Claude Code"))
        history.record(call(tool = "a", client = null))

        assertEquals(listOf("a", "b"), history.knownTools())
        assertEquals(listOf("Claude Code", "Cursor"), history.knownClients())
    }
}
