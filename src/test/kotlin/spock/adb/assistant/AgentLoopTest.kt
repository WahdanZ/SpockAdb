package spock.adb.assistant

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/** A client that replays a script, so the loop is tested and the provider is not. */
private class ScriptedClient(private val script: MutableList<() -> LlmResponse>) : LlmClient {
    val sentConversations = mutableListOf<List<LlmMessage>>()
    override fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): LlmResponse {
        sentConversations += messages
        val next = script.removeAt(0)()
        onTextDelta(next.text)
        return next
    }
}

private class RecordingTools(
    private val answer: (LlmToolCall) -> LlmToolResult = { LlmToolResult(it.id, "ok", isError = false) },
) : AgentTools {
    val invoked = mutableListOf<String>()
    override fun specs() = listOf(ToolSpec("android_list_devices", "List devices.", JsonObject()))
    override fun invoke(call: LlmToolCall): LlmToolResult {
        invoked += call.name
        return answer(call)
    }
}

private fun call(id: String, name: String = "android_list_devices") = LlmToolCall(id, name, JsonObject())

class AgentLoopTest {

    private val conversation = mutableListOf<LlmMessage>()
    private val streamed = StringBuilder()

    private fun run(
        client: LlmClient,
        tools: AgentTools = RecordingTools(),
        cap: Int = AgentLoop.MAX_ITERATIONS,
        cancelled: () -> Boolean = { false },
    ) = AgentLoop(client, tools, cap).run("sys", "why did it crash?", conversation, { streamed.append(it) }, cancelled)

    @Test
    fun `an answer with no tool calls ends the turn`() {
        val outcome = run(ScriptedClient(mutableListOf({ LlmResponse("Because of an NPE.") })))

        assertEquals(AgentOutcome.Answered("Because of an NPE."), outcome)
        assertEquals("Because of an NPE.", streamed.toString())
    }

    @Test
    fun `a tool call is run and its result fed back before the model answers`() {
        val tools = RecordingTools()
        val outcome = run(
            ScriptedClient(
                mutableListOf(
                    { LlmResponse("Checking.", toolCalls = listOf(call("t1"))) },
                    { LlmResponse("One device.") },
                ),
            ),
            tools,
        )

        assertEquals(AgentOutcome.Answered("One device."), outcome)
        assertEquals(listOf("android_list_devices"), tools.invoked)
        // user, assistant(+call), user(result), assistant
        assertEquals(4, conversation.size)
        assertEquals("t1", conversation[2].toolResults.single().id)
    }

    @Test
    fun `parallel calls come back in one message, in the order asked for`() {
        val tools = RecordingTools()
        run(
            ScriptedClient(
                mutableListOf(
                    { LlmResponse("", toolCalls = listOf(call("a", "first"), call("b", "second"))) },
                    { LlmResponse("done") },
                ),
            ),
            tools,
        )

        val results = conversation[2].toolResults
        assertEquals(listOf("a", "b"), results.map { it.id })
        assertEquals(listOf("first", "second"), tools.invoked)
    }

    @Test
    fun `a denied confirmation is fed back to the model rather than ending the turn`() {
        // The developer said no. The model must be told so it can do something else — this is
        // the case that separates a safety gate from a crash.
        val denied = RecordingTools { LlmToolResult(it.id, "The developer declined this call.", isError = true) }
        val outcome = run(
            ScriptedClient(
                mutableListOf(
                    { LlmResponse("Clearing.", toolCalls = listOf(call("t1", "android_clear_app_data"))) },
                    { LlmResponse("Understood — I will not clear it.") },
                ),
            ),
            denied,
        )

        assertEquals(AgentOutcome.Answered("Understood — I will not clear it."), outcome)
        val result = conversation[2].toolResults.single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("declined"))
    }

    @Test
    fun `the iteration cap stops a model that keeps calling tools`() {
        val endless = ScriptedClient(MutableList(10) { { LlmResponse("again", toolCalls = listOf(call("t"))) } })

        val outcome = run(endless, cap = 3)

        assertEquals(AgentOutcome.ReachedIterationCap("again", 3), outcome)
    }

    @Test
    fun `cancelling before the tools run means they do not run`() {
        val cancel = AtomicBoolean(false)
        val tools = RecordingTools()
        // Stop is pressed while the model is still asking for the tool.
        val asksThenStopped = ScriptedClient(
            mutableListOf({
                cancel.set(true)
                LlmResponse("Clearing.", toolCalls = listOf(call("t1")))
            }),
        )

        val outcome = run(asksThenStopped, tools, cancelled = { cancel.get() })

        assertTrue(outcome is AgentOutcome.Cancelled, outcome.toString())
        assertTrue(tools.invoked.isEmpty(), "a cancelled turn must not still clear app data")
    }

    @Test
    fun `a refusal ends the turn instead of burning iterations`() {
        val outcome = run(
            ScriptedClient(mutableListOf({ LlmResponse("", stopReason = "refusal") })),
        )
        assertTrue(outcome is AgentOutcome.Refused, outcome.toString())
    }

    @Test
    fun `a provider failure is reported in the provider's own words`() {
        val failing = object : LlmClient {
            override fun send(
                system: String,
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                onTextDelta: (String) -> Unit,
                isCancelled: () -> Boolean,
            ): LlmResponse = throw LlmException("credit balance is too low")
        }

        val outcome = run(failing)

        assertEquals(AgentOutcome.Failed("credit balance is too low"), outcome)
    }

    @Test
    fun `the conversation grows across a turn so later requests carry the history`() {
        val client = ScriptedClient(
            mutableListOf(
                { LlmResponse("", toolCalls = listOf(call("t1"))) },
                { LlmResponse("done") },
            ),
        )
        run(client)

        assertEquals(1, client.sentConversations[0].size)
        assertEquals(3, client.sentConversations[1].size)
    }
}
