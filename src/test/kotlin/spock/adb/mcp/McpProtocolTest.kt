package spock.adb.mcp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety

class McpProtocolTest {

    private val context = FakeToolContext()
    private val calls = mutableListOf<McpCall>()
    private val protocol = McpProtocol(contextProvider = { context }, auditLog = { calls += it })

    private fun call(json: String) = protocol.handle(json)?.let { JsonParser.parseString(it).asJsonObject }

    @Test
    fun `initialize advertises the protocol version and server info`() {
        val result = call("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")!!
            .getAsJsonObject("result")

        assertEquals(McpProtocol.PROTOCOL_VERSION, result.get("protocolVersion").asString)
        assertEquals("spock-adb", result.getAsJsonObject("serverInfo").get("name").asString)
        assertTrue(result.getAsJsonObject("capabilities").has("tools"))
    }

    @Test
    fun `tools list exposes every registered tool with a schema`() {
        val tools = call("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
            .getAsJsonObject("result")
            .getAsJsonArray("tools")

        assertEquals(ToolRegistry.all().size, tools.size())
        tools.forEach { element ->
            val tool = element.asJsonObject
            assertTrue(tool.get("name").asString.isNotBlank())
            assertTrue(tool.get("description").asString.isNotBlank(), "every tool needs a description")
            assertEquals("object", tool.getAsJsonObject("inputSchema").get("type").asString)
        }
    }

    @Test
    fun `destructive tools are annotated so clients can flag them`() {
        val tools = call("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")!!
            .getAsJsonObject("result")
            .getAsJsonArray("tools")
            .associate { it.asJsonObject.get("name").asString to it.asJsonObject.getAsJsonObject("annotations") }

        ToolRegistry.bySafety(ToolSafety.DESTRUCTIVE).forEach { tool ->
            assertTrue(
                tools.getValue(tool.name).get("destructiveHint").asBoolean,
                "${tool.name} must be advertised as destructive",
            )
        }
        ToolRegistry.bySafety(ToolSafety.READ_ONLY).forEach { tool ->
            assertTrue(
                tools.getValue(tool.name).get("readOnlyHint").asBoolean,
                "${tool.name} must be advertised as read-only",
            )
        }
    }

    @Test
    fun `an unknown tool is a tool error, not a protocol error`() {
        // The agent can read and recover from a tool error; a JSON-RPC error is opaque to it.
        val result = call("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope"}}""")!!
            .getAsJsonObject("result")

        assertTrue(result.get("isError").asBoolean)
        assertTrue(result.getAsJsonArray("content").toString().contains("Unknown tool"))
    }

    @Test
    fun `a notification gets no response`() {
        assertNull(protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `malformed json is reported as a parse error`() {
        val error = call("not json")!!.getAsJsonObject("error")
        assertEquals(McpProtocol.PARSE_ERROR, error.get("code").asInt)
    }

    @Test
    fun `an unknown method is reported as method not found`() {
        val error = call("""{"jsonrpc":"2.0","id":5,"method":"does/notExist"}""")!!.getAsJsonObject("error")
        assertEquals(McpProtocol.METHOD_NOT_FOUND, error.get("code").asInt)
    }

    @Test
    fun `listing devices returns the connected device and is audited`() {
        val result = call(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call",
               "params":{"name":"android_list_devices","arguments":{}}}""",
        )!!.getAsJsonObject("result")

        assertFalse(result.get("isError").asBoolean)
        assertTrue(result.getAsJsonArray("content").toString().contains("emulator-5554"))
        assertEquals(listOf("android_list_devices"), calls.map { it.toolName })
    }

    @Test
    fun `resources are listed and readable`() {
        val resources = call("""{"jsonrpc":"2.0","id":7,"method":"resources/list"}""")!!
            .getAsJsonObject("result")
            .getAsJsonArray("resources")
        assertTrue(resources.size() > 0)

        val body = call(
            """{"jsonrpc":"2.0","id":8,"method":"resources/read","params":{"uri":"android://devices"}}""",
        )!!.getAsJsonObject("result").getAsJsonArray("contents").first().asJsonObject.get("text").asString

        assertTrue(body.contains("emulator-5554"))
        assertTrue(body.contains("read at"), "resources must be stamped so staleness is visible")
    }

    @Test
    fun `every response carries the request id`() {
        val response = call("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""")!!
        assertEquals("abc", response.get("id").asString)
        assertNotNull(response.get("result"))
    }
}
