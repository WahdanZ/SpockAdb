package spock.adb.assistant

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.FakeToolContext
import spock.adb.mcp.McpCall
import spock.adb.mcp.tools.ToolRegistry

/**
 * The assistant runs against the same registry and the same switch as the MCP transports, so
 * what these cover is the one place the two deliberately differ: the assistant is handed a
 * filtered tool list, where a transport lists everything and refuses on call.
 */
class RegistryAgentToolsTest {

    private val context = FakeToolContext()
    private val audited = mutableListOf<McpCall>()

    private fun tools(enabled: (String) -> Boolean = { true }) = RegistryAgentTools(
        contextProvider = { context },
        audit = { audited += it },
        isToolEnabled = enabled,
    )

    private fun listDevices(name: String = "android_list_devices") =
        LlmToolCall(id = "call-1", name = name, arguments = JsonObject())

    @Test
    fun `the model is offered only the tools it may actually use`() {
        // Offering one that is certain to refuse would spend a turn and a tool call to learn
        // something the list could have said.
        val specs = tools { it != "android_run_adb_command" }.specs().map { it.name }

        assertEquals(ToolRegistry.all().size - 1, specs.size)
        assertFalse(specs.contains("android_run_adb_command"))
    }

    @Test
    fun `a tool switched off mid-conversation refuses rather than running`() {
        // The filtered list is not enough on its own: the model is reasoning from a history
        // that still contains the tool it was offered a turn ago.
        val result = tools { false }.invoke(listDevices())

        assertTrue(result.isError)
        assertTrue(result.content.contains("disabled"), result.content)
        assertFalse(result.content.contains("emulator-5554"), result.content)
    }

    @Test
    fun `a blocked attempt reaches the same audit trail as a call that ran`() {
        tools { false }.invoke(listDevices())

        assertEquals(listOf("android_list_devices"), audited.map { it.toolName })
        assertEquals(RegistryAgentTools.ASSISTANT_CLIENT, audited.single().client)
        assertTrue(audited.single().isError)
    }

    @Test
    fun `an enabled tool runs and is audited`() {
        val result = tools().invoke(listDevices())

        assertFalse(result.isError, result.content)
        assertTrue(result.content.contains("emulator-5554"), result.content)
        assertEquals(listOf("android_list_devices"), audited.map { it.toolName })
    }

    @Test
    fun `a tool that does not exist is answered in words, not an exception`() {
        val result = tools().invoke(listDevices(name = "android_no_such_tool"))

        assertTrue(result.isError)
        assertTrue(result.content.contains("no tool called"), result.content)
    }
}
