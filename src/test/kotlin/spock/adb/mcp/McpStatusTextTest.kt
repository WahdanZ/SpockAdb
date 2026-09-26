package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `green while serving, amber on a mismatch, grey when stopped`() {
        assertEquals(StatusDot.RUNNING, McpStatusText.color(running = true, mismatch = null))
        assertEquals(StatusDot.MISMATCH, McpStatusText.color(running = true, mismatch = "R58M"))
        assertEquals(StatusDot.STOPPED, McpStatusText.color(running = false, mismatch = null))
    }

    @Test
    fun `an agent on another device is a mismatch while the server runs`() {
        assertEquals("R58M", McpStatusText.mismatch(running = true, targeted = "R58M", selected = "emulator-5554"))
        assertNull(McpStatusText.mismatch(running = false, targeted = "R58M", selected = "emulator-5554"))
    }

    @Test
    fun `no mismatch when the agent is on the selected device or chose none`() {
        assertNull(McpStatusText.mismatch(running = true, targeted = "R58M", selected = "R58M"))
        assertNull(McpStatusText.mismatch(running = true, targeted = null, selected = "R58M"))
    }

    @Test
    fun `no mismatch while no device is selected yet`() {
        // The device list is still loading when a project opens; that is not an agent elsewhere.
        assertNull(McpStatusText.mismatch(running = true, targeted = "R58M", selected = null))
    }
}
