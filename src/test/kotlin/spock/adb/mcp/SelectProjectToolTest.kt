package spock.adb.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.SelectProjectTool
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety

/**
 * `android_select_project` is how an agent resolves the ambiguity that two open projects
 * create. Without it the ambiguity error would name a fix the agent cannot perform.
 */
class SelectProjectToolTest {

    private val context = FakeToolContext(openProjects = listOf("app", "sdk"))

    @Test
    fun `selecting a project reports it and the application id it implies`() {
        val result = SelectProjectTool().execute(
            JsonObject().apply { addProperty("projectName", "sdk") },
            context,
        )

        assertTrue(!result.isError)
        assertEquals("sdk", context.selectedProject)
        // The application ID is what the selection actually changes, so it is reported back.
        assertTrue(result.text().contains("com.example.app"), result.text())
    }

    @Test
    fun `an unknown project name lists the ones that are open`() {
        val failure = runCatching {
            SelectProjectTool().execute(
                JsonObject().apply { addProperty("projectName", "nope") },
                context,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("app, sdk"), failure.message.orEmpty())
    }

    @Test
    fun `a missing project name is rejected rather than guessed at`() {
        assertTrue(
            runCatching { SelectProjectTool().execute(JsonObject(), context) }.isFailure,
        )
    }

    @Test
    fun `it is registered, and selecting a project is not a read-only operation`() {
        val tool = ToolRegistry.find("android_select_project")
        assertNotNull(tool)
        assertEquals(ToolSafety.SAFE_ACTION, tool!!.safety)
    }

    @Test
    fun `a tool needing a project fails with the reason, not a bare null`() {
        // McpProtocol turns a thrown IllegalStateException into a tool error result, so the
        // agent reads the actionable message rather than "no project is open" for a case
        // that is really "say which project".
        val protocol = McpProtocol(contextProvider = { FakeToolContext(project = null) })
        val response = protocol.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                """"params":{"name":"android_get_current_activity","arguments":{}}}""",
        )!!

        val result = JsonParser.parseString(response).asJsonObject.getAsJsonObject("result")
        assertTrue(result.get("isError").asBoolean)
        assertTrue(
            result.getAsJsonArray("content").first().asJsonObject.get("text").asString
                .contains("No project is open"),
        )
    }

    private fun spock.adb.mcp.tools.ToolResult.text(): String =
        content.filterIsInstance<spock.adb.mcp.tools.ToolContent.Text>().joinToString("\n") { it.text }
}
