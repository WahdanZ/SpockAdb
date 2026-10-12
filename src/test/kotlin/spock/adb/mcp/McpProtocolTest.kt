package spock.adb.mcp

import com.google.gson.JsonObject
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

        assertEquals(McpSession.LATEST_VERSION, result.get("protocolVersion").asString)
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

    @Test
    fun `a disabled tool refuses, says why, and never reaches the device`() {
        val disabled = McpProtocol(
            contextProvider = { context },
            auditLog = { calls += it },
            isToolEnabled = { it != "android_list_devices" },
        )

        val result = JsonParser.parseString(
            disabled.handle(
                """{"jsonrpc":"2.0","id":9,"method":"tools/call",
                   "params":{"name":"android_list_devices","arguments":{}}}""",
            )!!,
        ).asJsonObject.getAsJsonObject("result")

        assertTrue(result.get("isError").asBoolean)
        val text = result.getAsJsonArray("content").toString()
        assertTrue(text.contains("disabled"), text)
        assertTrue(text.contains("android_list_devices"), text)
        // The device would have been named had the tool run at all.
        assertFalse(text.contains("emulator-5554"), text)
    }

    @Test
    fun `a blocked attempt is still audited`() {
        // Otherwise the one kind of call worth reviewing — an agent reaching for something it
        // was denied — would be the one kind that leaves no trace.
        val disabled = McpProtocol(
            contextProvider = { context },
            auditLog = { calls += it },
            isToolEnabled = { false },
        )

        disabled.handle(
            """{"jsonrpc":"2.0","id":10,"method":"tools/call",
               "params":{"name":"android_list_devices","arguments":{}}}""",
        )

        assertEquals(listOf("android_list_devices"), calls.map { it.toolName })
        assertTrue(calls.single().isError)
    }

    private fun initialize(target: McpProtocol, version: String?): JsonObject {
        val params = version?.let { ""","params":{"protocolVersion":"$it","clientInfo":{"name":"t"}}""" }.orEmpty()
        return JsonParser.parseString(target.handle("""{"jsonrpc":"2.0","id":20,"method":"initialize"$params}""")!!)
            .asJsonObject.getAsJsonObject("result")
    }

    private fun toolsOf(target: McpProtocol, handle: (String) -> String? = target::handle): Map<String, JsonObject> =
        JsonParser.parseString(handle("""{"jsonrpc":"2.0","id":21,"method":"tools/list"}""")!!)
            .asJsonObject.getAsJsonObject("result").getAsJsonArray("tools")
            .associate { it.asJsonObject.get("name").asString to it.asJsonObject }

    @Test
    fun `initialize echoes each version the server speaks, and offers the latest for any other`() {
        McpSession.SUPPORTED_VERSIONS.forEach { version ->
            val fresh = McpProtocol(contextProvider = { context })
            assertEquals(version, initialize(fresh, version).get("protocolVersion").asString)
        }
        listOf("2099-01-01", "1.0", null).forEach { asked ->
            val fresh = McpProtocol(contextProvider = { context })
            assertEquals("2025-06-18", initialize(fresh, asked).get("protocolVersion").asString, "asked $asked")
        }
    }

    @Test
    fun `serverInfo reports the plugin version, not a placeholder`() {
        val version = initialize(protocol, "2025-06-18").getAsJsonObject("serverInfo").get("version").asString

        assertEquals(PluginVersion.fromGradleProperties(), version)
    }

    @Test
    fun `each stdio session keeps the version it negotiated`() {
        val current = protocol.openSession()
        val older = protocol.openSession()
        current("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        older("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")

        assertTrue(toolsOf(protocol, current).getValue("flutter_app_status").has("outputSchema"))
        assertFalse(toolsOf(protocol, older).getValue("flutter_app_status").has("outputSchema"))
    }

    @Test
    fun `only a 2025-06-18 session sees output schemas and top-level titles`() {
        initialize(protocol, "2025-06-18")
        val current = toolsOf(protocol)
        listOf("android_get_debug_context", "android_diagnose_current_screen", "flutter_app_status").forEach {
            assertEquals("object", current.getValue(it).getAsJsonObject("outputSchema").get("type").asString, it)
        }
        assertEquals(3, current.values.count { it.has("outputSchema") })
        current.values.forEach { tool ->
            assertTrue(tool.get("title").asString.isNotBlank(), tool.get("name").asString)
            assertFalse(tool.getAsJsonObject("annotations").has("title"))
        }

        val older = McpProtocol(contextProvider = { context })
        initialize(older, "2025-03-26")
        toolsOf(older).values.forEach { tool ->
            assertFalse(tool.has("outputSchema") || tool.has("title"), tool.toString())
            assertTrue(tool.getAsJsonObject("annotations").get("title").asString.isNotBlank())
        }

        val oldest = McpProtocol(contextProvider = { context })
        initialize(oldest, "2024-11-05")
        toolsOf(oldest).values.forEach { tool ->
            assertFalse(tool.has("outputSchema") || tool.has("title"), tool.toString())
            assertFalse(tool.getAsJsonObject("annotations").has("title"), tool.toString())
        }
    }

    @Test
    fun `a 2025-06-18 result carries the JSON its text holds as structuredContent, and older ones do not`() {
        val call = """{"jsonrpc":"2.0","id":30,"method":"tools/call",
            "params":{"name":"flutter_app_status","arguments":{"packageName":"com.example.app"}}}"""

        initialize(protocol, "2025-06-18")
        val current = call(call)!!.getAsJsonObject("result")
        assertFalse(current.get("isError").asBoolean, current.toString())
        val text = current.getAsJsonArray("content").first().asJsonObject.get("text").asString
        assertEquals(JsonParser.parseString(text), current.get("structuredContent"))
        assertEquals("com.example.app", current.getAsJsonObject("structuredContent").get("applicationId").asString)

        val older = McpProtocol(contextProvider = { context })
        initialize(older, "2025-03-26")
        val legacy = JsonParser.parseString(older.handle(call)!!).asJsonObject.getAsJsonObject("result")
        assertFalse(legacy.has("structuredContent"), legacy.toString())
    }

    @Test
    fun `a tool without an output schema never gets structuredContent`() {
        initialize(protocol, "2025-06-18")
        val result = call(
            """{"jsonrpc":"2.0","id":31,"method":"tools/call",
               "params":{"name":"android_list_devices","arguments":{}}}""",
        )!!.getAsJsonObject("result")

        assertFalse(result.has("structuredContent"))
    }

    @Test
    fun `an out-of-range number is refused as a tool error naming the argument and its range`() {
        val result = call(
            """{"jsonrpc":"2.0","id":32,"method":"tools/call",
               "params":{"name":"android_get_logcat","arguments":{"maxLines":-5}}}""",
        )!!.getAsJsonObject("result")

        assertTrue(result.get("isError").asBoolean)
        assertEquals(
            "Argument 'maxLines' must be between 1 and 5000, got -5.",
            result.getAsJsonArray("content").first().asJsonObject.get("text").asString,
        )
    }

    @Test
    fun `the hints say what the safety level and the tool flags say`() {
        val annotations = toolsOf(protocol).mapValues { it.value.getAsJsonObject("annotations") }
        ToolRegistry.all().forEach { tool ->
            val hints = annotations.getValue(tool.name)
            val readOnly = tool.safety == ToolSafety.READ_ONLY
            assertEquals(readOnly, hints.get("readOnlyHint")?.asBoolean ?: false, tool.name)
            if (!readOnly) assertEquals(tool.destructiveHint, hints.get("destructiveHint").asBoolean, tool.name)
            assertEquals(tool.idempotent, hints.get("idempotentHint")?.asBoolean ?: false, tool.name)
            assertEquals(tool.openWorld, hints.has("openWorldHint"), tool.name)
        }
        listOf(
            "android_set_battery_level",
            "android_unplug_battery",
            "android_set_charger",
            "android_set_standby_bucket",
            "android_run_recipe",
        ).forEach { assertTrue(annotations.getValue(it).get("destructiveHint").asBoolean, it) }
    }

    @Test
    fun `a disabled tool is still listed and still described`() {
        // Hiding it would send an agent hunting for a tool it can see documented, and would
        // make "you turned this off" indistinguishable from "this build does not have it".
        val disabled = McpProtocol(contextProvider = { context }, isToolEnabled = { false })

        val tools = JsonParser.parseString(
            disabled.handle("""{"jsonrpc":"2.0","id":11,"method":"tools/list"}""")!!,
        ).asJsonObject.getAsJsonObject("result").getAsJsonArray("tools")

        assertEquals(ToolRegistry.all().size, tools.size())
    }
}
