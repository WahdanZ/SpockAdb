package spock.adb.mcp

import spock.adb.mcp.tools.ToolSafety

/**
 * Bounded, searchable history of MCP tool calls.
 *
 * Bounded on purpose: an agent can issue hundreds of calls a minute, and an unlimited log
 * would grow the heap and keep device data around far longer than anyone intended. The cap
 * is configurable but never absent.
 */
class McpRequestHistory(capacity: Int = DEFAULT_CAPACITY) {

    var capacity: Int = capacity
        set(value) {
            field = value.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
            synchronized(this) { trim() }
        }

    private val calls = ArrayDeque<McpCall>()

    @Synchronized
    fun record(call: McpCall) {
        calls.addLast(call)
        trim()
    }

    /** Newest first, which is the order the activity panel shows. */
    @Synchronized
    fun all(): List<McpCall> = calls.toList().asReversed()

    /**
     * Inserts [older] ahead of everything already here, oldest first.
     *
     * Used to bring the persisted history back at startup. It prepends rather than replaces
     * because the load runs off the EDT and a call can be recorded while it is in flight —
     * replacing would throw that call away, which is the one the developer is watching for. The
     * caller loads once; loading twice would double the entries.
     */
    @Synchronized
    fun prepend(older: List<McpCall>) {
        val live = calls.toList()
        calls.clear()
        calls.addAll(older)
        calls.addAll(live)
        trim()
    }

    @Synchronized
    fun clear() = calls.clear()

    @Synchronized
    fun size(): Int = calls.size

    @Synchronized
    fun query(filter: McpHistoryFilter): List<McpCall> = all().filter(filter::matches)

    /** Tool names seen so far, for populating a filter dropdown. */
    @Synchronized
    fun knownTools(): List<String> = calls.map { it.toolName }.distinct().sorted()

    @Synchronized
    fun knownClients(): List<String> = calls.mapNotNull { it.client }.distinct().sorted()

    private fun trim() {
        while (calls.size > capacity) calls.removeFirst()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
        const val MIN_CAPACITY = 50
        const val MAX_CAPACITY = 5_000
    }
}

/** Filtering for the activity view. Pure, so the rules are directly testable. */
data class McpHistoryFilter(
    val query: String = "",
    val tool: String? = null,
    val client: String? = null,
    val deviceSerial: String? = null,
    val outcome: Outcome = Outcome.ANY,
    val safety: ToolSafety? = null,
) {
    enum class Outcome { ANY, SUCCESS, ERROR }

    // The branch count is the number of independent filter criteria; collapsing them would
    // make the rules harder to read, not easier.
    @Suppress("CyclomaticComplexMethod")
    fun matches(call: McpCall): Boolean {
        if (tool != null && call.toolName != tool) return false
        if (client != null && call.client != client) return false
        if (deviceSerial != null && call.deviceSerial != deviceSerial) return false
        if (safety != null && call.safety != safety) return false
        when (outcome) {
            Outcome.SUCCESS -> if (call.isError) return false
            Outcome.ERROR -> if (!call.isError) return false
            Outcome.ANY -> Unit
        }
        if (query.isBlank()) return true

        return call.toolName.contains(query, ignoreCase = true) ||
            call.arguments.contains(query, ignoreCase = true) ||
            call.result.contains(query, ignoreCase = true)
    }
}
