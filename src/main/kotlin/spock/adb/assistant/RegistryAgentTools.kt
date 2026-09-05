package spock.adb.assistant

import spock.adb.mcp.McpCall
import spock.adb.mcp.ToolGate
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
    /** The same switch the MCP transports consult — one setting, both ways in. */
    private val isToolEnabled: (String) -> Boolean = { true },
) : AgentTools {

    /**
     * Only the tools that may actually run.
     *
     * Unlike the MCP transports, which list everything and refuse on call because a client may
     * cache a tool list across a settings change, the model is given a fresh list every turn.
     * Offering it a tool that is certain to refuse would spend a turn and a tool call to learn
     * something the list could have said.
     */
    override fun specs(): List<ToolSpec> =
        ToolRegistry.all().filter { isToolEnabled(it.name) }.toToolSpecs()

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
        // The check is still needed despite the filtered list: a tool switched off part-way
        // through a conversation is still in the history the model is reasoning from. It falls
        // through to the audit below rather than returning early, so a blocked attempt shows in
        // the Activity tab exactly as one over MCP does.
        val result = if (!isToolEnabled(call.name)) {
            ToolResult.error(ToolGate.refusal(call.name))
        } else {
            try {
                tool.execute(call.arguments, contextProvider())
            } catch (e: Exception) {
                // Includes a declined confirmation, which reaches here as a thrown refusal. The
                // model needs to be told "no" in words; silence would look like a tool that hung.
                ToolResult.error(e.message ?: "${e.javaClass.simpleName} while running ${call.name}")
            }
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
