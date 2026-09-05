package spock.adb.assistant

import spock.adb.mcp.McpCall
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolResult

/**
 * The assistant's tools: the MCP registry, unchanged.
 *
 * There is no `AssistantToolContext` class, though the plan named one. [ToolContext] is already
 * "the same `DeviceLister` and `DebugBridgeProvider`, reusing the confirmation dialog" — that
 * is `McpToolContext`'s entire job — so a subclass of it would add a name and no behaviour, and
 * would be a second place for the safety model to drift to. The assistant is handed the same
 * context the MCP transports are handed, which is the invariant the plan asks for rather than
 * merely a way to satisfy it.
 *
 * @param audit every call, recorded with the client set to [ASSISTANT_CLIENT] so the Activity
 *   tab shows what the assistant did next to what an external agent did. One trail, one place
 *   to look when the question is "what touched my device".
 */
class RegistryAgentTools(
    private val contextProvider: () -> ToolContext,
    private val audit: (McpCall) -> Unit = {},
) : AgentTools {

    override fun specs(): List<ToolSpec> = ToolRegistry.all().toToolSpecs()

    // A tool boundary must not be able to end the conversation: whatever went wrong is a
    // result the model can read and act on, and an exception here would instead be a stack
    // trace where the developer expected an answer.
    @Suppress("TooGenericExceptionCaught")
    override fun invoke(call: LlmToolCall): LlmToolResult {
        val tool = ToolRegistry.find(call.name)
            ?: return LlmToolResult(
                call.id,
                "There is no tool called '${call.name}'. Use one of the tools you were given.",
                isError = true,
            )

        val startedAt = System.currentTimeMillis()
        val result = try {
            tool.execute(call.arguments, contextProvider())
        } catch (e: Exception) {
            // Includes a declined confirmation, which reaches here as a thrown refusal. The
            // model needs to be told "no" in words; silence would look like a tool that hung.
            ToolResult.error(e.message ?: "${e.javaClass.simpleName} while running ${call.name}")
        }

        val text = result.content.joinToString("\n") { item ->
            when (item) {
                is ToolContent.Text -> item.text
                // An image is described, not inlined: a base64 screenshot would blow the
                // context window and cost real money to send back on every later turn.
                is ToolContent.Image -> "[${item.mimeType}, ${item.base64Data.length} base64 chars]"
            }
        }

        audit(
            McpCall(
                toolName = call.name,
                safety = tool.safety,
                arguments = call.arguments.toString(),
                result = text.take(RESULT_PREVIEW_CHARS),
                durationMs = System.currentTimeMillis() - startedAt,
                isError = result.isError,
                client = ASSISTANT_CLIENT,
                deviceSerial = call.arguments.get("deviceSerial")
                    ?.takeIf { !it.isJsonNull }?.asString,
            ),
        )

        return LlmToolResult(call.id, text, result.isError)
    }

    companion object {
        /** What the Activity tab shows in the client column for an in-IDE assistant call. */
        const val ASSISTANT_CLIENT = "spock-assistant"
        private const val RESULT_PREVIEW_CHARS = 4_000
    }
}
