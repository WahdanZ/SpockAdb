package spock.adb.assistant

/**
 * The tools the loop may call, and the one place they are run.
 *
 * An interface rather than `ToolRegistry` directly, so the loop can be tested without an IDE,
 * a device or a registry of 42 real tools. The production implementation is a thin adapter:
 * the registry stays the single definition of what an agent may do, and the safety model stays
 * where it already is.
 */
interface AgentTools {

    fun specs(): List<ToolSpec>

    /**
     * Runs one call and returns what the model should see.
     *
     * Must not throw. A tool that failed, was refused by the developer, or does not exist is
     * information the model needs in order to do something else; an exception thrown here
     * would end the conversation instead, at the moment it became interesting.
     */
    fun invoke(call: LlmToolCall): LlmToolResult
}

/** Why the loop stopped. The caller shows a different thing for each. */
sealed interface AgentOutcome {

    /** The model finished its turn. */
    data class Answered(val text: String) : AgentOutcome

    /** The developer pressed Stop. Whatever had streamed is already on screen. */
    data class Cancelled(val text: String) : AgentOutcome

    /**
     * The loop hit its ceiling with the model still calling tools.
     *
     * Reported rather than silently truncated: an agent going round in circles is a bug or a
     * bill, and either way the developer should be told which.
     */
    data class ReachedIterationCap(val text: String, val cap: Int) : AgentOutcome

    /** The provider declined the request. Retrying it would spend money to be declined again. */
    data class Refused(val text: String) : AgentOutcome

    /** The provider failed. Carries its own words. */
    data class Failed(val message: String) : AgentOutcome
}

/**
 * The model ⇄ tool cycle.
 *
 * Synchronous, on a pooled thread, with cancellation as a polled predicate rather than a
 * thread interrupt — `docs/COMPATIBILITY.md` rules out coroutines, and interrupting a thread
 * mid-request tears down the HTTP connection instead of ending the turn cleanly.
 */
class AgentLoop(
    private val client: LlmClient,
    private val tools: AgentTools,
    private val maxIterations: Int = MAX_ITERATIONS,
) {

    /**
     * @param conversation appended to in place, so the caller keeps the transcript across
     *   turns and a cancelled turn still leaves the history consistent.
     */
    // Six exits, each naming a different outcome the caller renders differently. Folding them
    // into one result variable would trade six clear names for one mutable one.
    @Suppress("ReturnCount")
    fun run(
        system: String,
        userMessage: String,
        conversation: MutableList<LlmMessage>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): AgentOutcome {
        conversation += LlmMessage(LlmMessage.Role.USER, text = userMessage)
        val specs = tools.specs()
        var lastText = ""

        repeat(maxIterations) {
            if (isCancelled()) return AgentOutcome.Cancelled(lastText)

            val response = try {
                client.send(system, conversation.toList(), specs, onTextDelta, isCancelled)
            } catch (e: LlmException) {
                return AgentOutcome.Failed(e.message ?: "The provider failed without saying why.")
            }

            lastText = response.text
            conversation += LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = response.text,
                toolCalls = response.toolCalls,
            )

            if (response.wasRefused) return AgentOutcome.Refused(response.text)
            if (response.toolCalls.isEmpty()) return AgentOutcome.Answered(response.text)
            // Cancelled while the model was asking for tools: stop before running any of them.
            // The alternative — clearing app data and then noticing Stop was pressed — is not
            // a race worth having.
            if (isCancelled()) return AgentOutcome.Cancelled(lastText)

            // Every result for this turn goes back in one message, in the order asked for.
            conversation += LlmMessage(
                role = LlmMessage.Role.USER,
                toolResults = response.toolCalls.map { tools.invoke(it) },
            )
        }

        return AgentOutcome.ReachedIterationCap(lastText, maxIterations)
    }

    companion object {
        /**
         * The only guard against a surprise bill in v1, so it is deliberately not generous.
         * A debugging task that genuinely needs more than this is one to drive by hand.
         */
        const val MAX_ITERATIONS = 25
    }
}
