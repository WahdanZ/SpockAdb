package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the MCP indicator in the status bar says. */
class McpStatusTextTest {

    @Test
    fun `off and on are one word each`() {
        assertEquals("MCP: off", McpStatusText.of(running = false, mismatch = null))
        assertEquals("MCP: on", McpStatusText.of(running = true, mismatch = null))
    }

    @Test
    fun `an agent on another device is said before anything else`() {
        assertEquals("⚠ MCP: agent on R58M", McpStatusText.of(running = true, mismatch = "R58M"))
        assertTrue(McpStatusText.tooltip(true, 8123, "R58M").contains("still apply to the device selected here"))
    }

    @Test
    fun `the port is in the tooltip when running`() {
        assertTrue(McpStatusText.tooltip(running = true, port = 8123, mismatch = null).contains("port 8123"))
    }
}
