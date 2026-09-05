package spock.adb.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The frame handling, driven from a canned stream.
 *
 * These are the cases that only appear on the wire: arguments split across frames, two calls
 * interleaved by index, and a stream that stops half way. None of them need a network or a key.
 */
class AnthropicStreamTest {

    private val streamed = StringBuilder()

    private fun parse(vararg lines: String, cancel: AtomicBoolean = AtomicBoolean(false)) =
        AnthropicStream.parse(lines.iterator(), { streamed.append(it) }, { cancel.get() })

    private fun data(json: String) = "data: $json"

    private fun textDelta(text: String) = data(
        """{"type":"content_block_delta","index":0,""" +
            """"delta":{"type":"text_delta","text":"$text"}}""",
    )

    private fun toolStart(index: Int, id: String, name: String) = data(
        """{"type":"content_block_start","index":$index,""" +
            """"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""",
    )

    private fun argumentFragment(index: Int, fragment: String) = data(
        """{"type":"content_block_delta","index":$index,""" +
            """"delta":{"type":"input_json_delta","partial_json":"$fragment"}}""",
    )

    private fun stop(reason: String) =
        data("""{"type":"message_delta","delta":{"stop_reason":"$reason"}}""")

    @Test
    fun `text deltas are streamed as they arrive and accumulated`() {
        val response = parse(
            "event: message_start",
            data("""{"type":"message_start","message":{"id":"msg_1"}}"""),
            textDelta("Hel"),
            textDelta("lo"),
            stop("end_turn"),
            data("""{"type":"message_stop"}"""),
        )

        assertEquals("Hello", response.text)
        assertEquals("Hello", streamed.toString(), "text should reach the transcript as it arrives")
        assertEquals("end_turn", response.stopReason)
        assertTrue(response.toolCalls.isEmpty())
    }

    @Test
    fun `a tool call is assembled from fragments that are not valid json alone`() {
        val response = parse(
            toolStart(0, "toolu_1", "android_get_logcat"),
            argumentFragment(0, """{\"maxLi"""),
            argumentFragment(0, """nes\":20}"""),
            stop("tool_use"),
        )

        val call = response.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("android_get_logcat", call.name)
        assertEquals(20, call.arguments.get("maxLines").asInt)
        assertEquals("tool_use", response.stopReason)
    }

    @Test
    fun `parallel tool calls are kept apart by their block index`() {
        // Fragments arrive interleaved and out of order; only the index says which is which.
        val response = parse(
            toolStart(0, "a", "first"),
            toolStart(1, "b", "second"),
            argumentFragment(1, """{\"x\":2}"""),
            argumentFragment(0, """{\"x\":1}"""),
        )

        assertEquals(listOf("first", "second"), response.toolCalls.map { it.name })
        assertEquals(1, response.toolCalls[0].arguments.get("x").asInt)
        assertEquals(2, response.toolCalls[1].arguments.get("x").asInt)
    }

    @Test
    fun `a tool call with no arguments still runs`() {
        val response = parse(toolStart(0, "a", "android_list_devices"))

        assertEquals(1, response.toolCalls.size)
        assertEquals(0, response.toolCalls.single().arguments.size())
    }

    @Test
    fun `a half-streamed tool call is dropped rather than run with broken arguments`() {
        val response = parse(
            toolStart(0, "a", "android_clear_app_data"),
            argumentFragment(0, """{\"packageNam"""),
        )

        assertTrue(response.toolCalls.isEmpty(), "half a tool call must never be executed")
    }

    @Test
    fun `cancellation stops reading and returns what arrived`() {
        val cancel = AtomicBoolean(false)
        val lines = listOf(textDelta("before"), textDelta("after"))

        val response = AnthropicStream.parse(
            lines.iterator(),
            {
                streamed.append(it)
                cancel.set(true)
            },
            { cancel.get() },
        )

        assertEquals("before", response.text)
    }

    @Test
    fun `a mid-stream error is raised rather than returned as an empty answer`() {
        val failure = assertThrows<LlmException> {
            parse(data("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        }

        assertTrue(failure.message!!.contains("Overloaded"), failure.message)
    }

    @Test
    fun `a refusal is reported as one, not as an empty response`() {
        assertTrue(parse(stop("refusal")).wasRefused)
    }

    @Test
    fun `non-data lines and keepalives are ignored`() {
        val response = parse("event: ping", "", ": keepalive", data("[DONE]"), textDelta("ok"))

        assertEquals("ok", response.text)
    }
}
