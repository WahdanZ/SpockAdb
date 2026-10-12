package spock.adb.mcp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keeps `tools/list` within budget.
 *
 * A client sends the whole list to its model on every turn, so its size is a cost an agent pays
 * whether or not it calls a tool. The review that prompted this measured 62,026 characters
 * (~15K tokens), and it had grown to 71,680 by the time it was trimmed: one deviceSerial
 * description repeated in 67 tools, eight selector parameters in nine, and paragraph-long tool
 * descriptions. A new tool, or a longer description, has to fit in what is left.
 */
class ToolsListSizeTest {

    private fun size(version: String?): Int {
        val protocol = McpProtocol(contextProvider = { FakeToolContext() })
        version?.let {
            protocol.handle("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"$it"}}""")
        }
        val response = protocol.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!
        return JsonParser.parseString(response).asJsonObject.getAsJsonObject("result").toString().length
    }

    @Test
    fun `the list a 2024-11-05 client sees, the shape the review measured, stays within 40,000 characters`() {
        val size = size("2024-11-05")

        assertTrue(size <= LEGACY_BUDGET, "tools/list is $size characters; the budget is $LEGACY_BUDGET")
    }

    @Test
    fun `newer clients get titles and output schemas on top, and stay within their budget`() {
        // The same list plus what those protocol versions add: a title on every tool, and for
        // 2025-06-18 the output schemas of the three tools that return structured content.
        listOf("2025-03-26", "2025-06-18").forEach { version ->
            val size = size(version)
            assertTrue(size <= CURRENT_BUDGET, "tools/list for $version is $size characters, over $CURRENT_BUDGET")
        }
    }

    private companion object {
        const val LEGACY_BUDGET = 40_000
        const val CURRENT_BUDGET = 43_000
    }
}
