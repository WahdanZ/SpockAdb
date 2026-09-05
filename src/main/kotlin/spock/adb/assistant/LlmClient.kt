package spock.adb.assistant

import com.google.gson.JsonObject

/**
 * One turn of a conversation, in the shape both providers can carry.
 *
 * Deliberately not the provider's own wire format: Anthropic nests tool calls as content
 * blocks and OpenAI hangs them off the message, and a loop written against either shape would
 * have to be rewritten for the other.
 */
data class LlmMessage(
    val role: Role,
    val text: String? = null,
    /** Tool calls the model asked for, on an [Role.ASSISTANT] turn. */
    val toolCalls: List<LlmToolCall> = emptyList(),
    /** Results being handed back, on a [Role.USER] turn. */
    val toolResults: List<LlmToolResult> = emptyList(),
) {
    enum class Role { USER, ASSISTANT }
}

/** @param id the provider's own id for the call, echoed back with the result. */
data class LlmToolCall(val id: String, val name: String, val arguments: JsonObject)

data class LlmToolResult(val id: String, val content: String, val isError: Boolean)

/** A tool as the provider wants it described. Built from [AdbTool] and never written twice. */
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * What the model did with a turn.
 *
 * [toolCalls] being non-empty is the loop's signal to run them and go round again; empty means
 * the turn is finished and [text] is the answer.
 */
data class LlmResponse(
    val text: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val stopReason: String? = null,
) {
    /**
     * True when the provider declined the request outright rather than answering it.
     *
     * Worth distinguishing from an empty answer: retrying it or feeding it back to the loop
     * would burn iterations on a request that is not going to be served.
     */
    val wasRefused: Boolean get() = stopReason == "refusal"
}

/**
 * A synchronous chat completion, called from a pooled thread.
 *
 * Synchronous and callback-streamed on purpose. `docs/COMPATIBILITY.md` rules out coroutines
 * in this plugin — a `suspend` function compiles to a state machine that references stdlib
 * symbols missing from the Kotlin runtime bundled with 2023.1 IDEs, and the failure appears
 * only in the IDE, never in a unit test.
 */
interface LlmClient {

    /**
     * @param onTextDelta called on the calling thread as text arrives, for streaming into the
     *   transcript. Never called after [send] returns.
     * @param isCancelled polled between events; when it goes true, [send] stops reading and
     *   returns what it has. A predicate rather than a `Thread.interrupt`, so a cancelled turn
     *   ends at a message boundary instead of tearing the HTTP connection down mid-frame.
     * @throws LlmException with the provider's own message when the call fails. No retries:
     *   an agent loop that silently retries a 400 spends the developer's money on the same
     *   rejected request several times over.
     */
    fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): LlmResponse
}

/** Carries the provider's message verbatim: a paraphrase loses the part that explains it. */
class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
