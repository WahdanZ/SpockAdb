package spock.adb.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolResult

/**
 * MCP over JSON-RPC 2.0.
 *
 * Transport-agnostic on purpose: it takes a request string and returns a response string,
 * so the same implementation serves the HTTP endpoint, a future stdio bridge, and the
 * protocol tests, without any of them needing a device.
 */
class McpProtocol(
    private val contextProvider: () -> ToolContext,
    private val auditLog: (McpCall) -> Unit = {},
) {

    /**
     * Handles one request.
     *
     * @return the JSON-RPC response, or null for a notification, which by spec gets no reply.
     */
    // The dispatcher's branch count is the JSON-RPC method list, and catching broadly is
    // required of a protocol boundary: an unexpected exception must become an error response,
    // not a dead connection that leaves the client waiting.
    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
    fun handle(rawRequest: String): String? {
        val request = try {
            JsonParser.parseString(rawRequest).asJsonObject
        } catch (e: Exception) {
            return error(null, PARSE_ERROR, "Invalid JSON: ${e.message}").toString()
        }

        val id = request.get("id")
        val method = request.get("method")?.asString
            ?: return error(id, INVALID_REQUEST, "Missing 'method'").toString()

        // Notifications carry no id and must not be answered.
        if (id == null || id.isJsonNull) {
            return null
        }

        return try {
            when (method) {
                "initialize" -> {
                    rememberClient(request.getAsJsonObject("params"))
                    success(id, initialize())
                }
                "ping" -> success(id, JsonObject())
                "tools/list" -> success(id, toolsList())
                "tools/call" -> success(id, toolsCall(request.getAsJsonObject("params")))
                "resources/list" -> success(id, resourcesList())
                "resources/read" -> success(id, resourcesRead(request.getAsJsonObject("params")))
                "prompts/list" -> success(id, JsonObject().apply { add("prompts", JsonArray()) })
                else -> error(id, METHOD_NOT_FOUND, "Unknown method '$method'")
            }.toString()
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, e.message ?: e.javaClass.simpleName).toString()
        }
    }

    /** Name reported by the connected client at `initialize`, when it reported one. */
    @Volatile
    var connectedClient: McpClientInfo? = null
        private set

    private fun rememberClient(params: JsonObject?) {
        val info = params?.getAsJsonObject("clientInfo") ?: return
        val name = info.get("name")?.asString ?: return
        connectedClient = McpClientInfo(
            name = name,
            version = info.get("version")?.asString,
            connectedAt = System.currentTimeMillis(),
        )
    }

    private fun initialize(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", PROTOCOL_VERSION)
        add(
            "capabilities",
            JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
                add("resources", JsonObject().apply { addProperty("subscribe", false) })
            },
        )
        add(
            "serverInfo",
            JsonObject().apply {
                addProperty("name", "spock-adb")
                addProperty("title", "Spock ADB — Android device tools")
                addProperty("version", SERVER_VERSION)
            },
        )
        addProperty(
            "instructions",
            "Tools for inspecting and driving a connected Android device. Call " +
                "android_list_devices first. Read-only tools are safe to call freely; tools " +
                "that destroy state (clear app data, revoke permissions, arbitrary shell) " +
                "require the developer to approve each call and may be declined.",
        )
    }

    private fun toolsList(): JsonObject = JsonObject().apply {
        add(
            "tools",
            JsonArray().apply {
                ToolRegistry.all().forEach { add(it.describe()) }
            },
        )
    }

    private fun AdbTool.describe(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", inputSchema)
        // Advertise the safety level so a client can surface it, and so the destructive
        // tools are visibly different rather than looking like every other call.
        add(
            "annotations",
            JsonObject().apply {
                addProperty("readOnlyHint", safety == spock.adb.mcp.tools.ToolSafety.READ_ONLY)
                addProperty("destructiveHint", safety == spock.adb.mcp.tools.ToolSafety.DESTRUCTIVE)
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun toolsCall(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.asString
            ?: throw IllegalArgumentException("Missing tool name")
        val arguments = params.getAsJsonObject("arguments") ?: JsonObject()

        val tool = ToolRegistry.find(name)
            ?: return ToolResult.error(
                "Unknown tool '$name'. Call tools/list to see what is available.",
            ).toJson()

        val startedAt = System.currentTimeMillis()
        val result = try {
            tool.execute(arguments, contextProvider())
        } catch (e: Exception) {
            // Surfaced as a tool error rather than a protocol error: the agent can read it,
            // explain it to the user and try something else, which a JSON-RPC error hides.
            ToolResult.error(e.message ?: "${e.javaClass.simpleName} while running $name")
        }

        auditLog(
            McpCall(
                toolName = name,
                safety = tool.safety,
                arguments = arguments.toString(),
                result = result.summarise(),
                durationMs = System.currentTimeMillis() - startedAt,
                isError = result.isError,
                client = connectedClient?.name,
                deviceSerial = arguments.get("deviceSerial")?.takeIf { !it.isJsonNull }?.asString,
            ),
        )
        return result.toJson()
    }

    private fun resourcesList(): JsonObject = JsonObject().apply {
        add(
            "resources",
            JsonArray().apply {
                McpResources.all().forEach { resource ->
                    add(
                        JsonObject().apply {
                            addProperty("uri", resource.uri)
                            addProperty("name", resource.name)
                            addProperty("description", resource.description)
                            addProperty("mimeType", resource.mimeType)
                        },
                    )
                }
            },
        )
    }

    private fun resourcesRead(params: JsonObject?): JsonObject {
        val uri = params?.get("uri")?.asString
            ?: throw IllegalArgumentException("Missing resource uri")
        val resource = McpResources.find(uri)
            ?: throw IllegalArgumentException("Unknown resource '$uri'")

        val body = resource.read(contextProvider())
        return JsonObject().apply {
            add(
                "contents",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("uri", uri)
                            addProperty("mimeType", resource.mimeType)
                            addProperty("text", body)
                        },
                    )
                },
            )
        }
    }

    /**
     * A short, readable form of the result for the activity panel.
     *
     * Image payloads are described rather than stored: a base64 screenshot is megabytes and
     * would make the bounded history meaningless.
     */
    private fun ToolResult.summarise(): String = content.joinToString("\n") { item ->
        when (item) {
            is ToolContent.Text -> item.text.take(RESULT_PREVIEW_CHARS)
            is ToolContent.Image -> "[${item.mimeType}, ${item.base64Data.length} base64 chars]"
        }
    }

    private fun ToolResult.toJson(): JsonObject = JsonObject().apply {
        add(
            "content",
            JsonArray().apply {
                this@toJson.content.forEach { item ->
                    add(
                        when (item) {
                            is ToolContent.Text -> JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", item.text)
                            }
                            is ToolContent.Image -> JsonObject().apply {
                                addProperty("type", "image")
                                addProperty("data", item.base64Data)
                                addProperty("mimeType", item.mimeType)
                            }
                        },
                    )
                }
            },
        )
        addProperty("isError", isError)
    }

    private fun success(id: JsonElement?, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun error(id: JsonElement?, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add(
            "error",
            JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            },
        )
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_VERSION = "1.0.0"

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
        const val RESULT_PREVIEW_CHARS = 4_000
    }
}

/** What a client told us about itself at `initialize`. Absent when it told us nothing. */
data class McpClientInfo(
    val name: String,
    val version: String?,
    val connectedAt: Long,
)

/**
 * One recorded tool invocation, for the activity panel and the audit trail.
 *
 * [client] is whatever the client reported in `initialize`. It is genuinely optional: the
 * HTTP transport is stateless, so a client that never calls `initialize` — or calls it on a
 * different connection — cannot be identified. It is left null rather than guessed.
 */
data class McpCall(
    val toolName: String,
    val safety: spock.adb.mcp.tools.ToolSafety,
    val arguments: String,
    val result: String,
    val durationMs: Long,
    val isError: Boolean,
    val client: String? = null,
    val deviceSerial: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** Destructive calls that succeeded were, by construction, confirmed by the developer. */
    val wasConfirmed: Boolean
        get() = safety == spock.adb.mcp.tools.ToolSafety.DESTRUCTIVE && !isError
}
