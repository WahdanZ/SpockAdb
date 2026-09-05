package spock.adb.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiStreamTest {

    private fun parse(vararg lines: String) =
        OpenAiStream.parse(lines.iterator(), {}, { false })

    private fun data(json: String) = "data: $json"

    private fun toolCallStart(id: String, name: String) = data(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id",""" +
            """"function":{"name":"$name","arguments":""}}]}}]}""",
    )

    private fun argumentFragment(fragment: String) = data(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,""" +
            """"function":{"arguments":"$fragment"}}]}}]}""",
    )

    private fun finish(reason: String) =
        data("""{"choices":[{"delta":{},"finish_reason":"$reason"}]}""")

    @Test
    fun `content deltas accumulate`() {
        val response = parse(
            data("""{"choices":[{"delta":{"content":"Hel"}}]}"""),
            data("""{"choices":[{"delta":{"content":"lo"}}]}"""),
            finish("stop"),
            data("[DONE]"),
        )
        assertEquals("Hello", response.text)
        assertEquals("stop", response.stopReason)
    }

    @Test
    fun `tool call arguments are stitched from fragments keyed by index`() {
        val response = parse(
            toolCallStart("c1", "android_get_logcat"),
            argumentFragment("""{\"maxLi"""),
            argumentFragment("""nes\":20}"""),
            finish("tool_calls"),
        )
        val call = response.toolCalls.single()
        assertEquals("c1", call.id)
        assertEquals("android_get_logcat", call.name)
        assertEquals(20, call.arguments.get("maxLines").asInt)
        // Mapped into the neutral vocabulary the loop reads.
        assertEquals("tool_use", response.stopReason)
    }

    @Test
    fun `a content filter finish maps onto the same refusal the loop already handles`() {
        assertTrue(parse(finish("content_filter")).wasRefused)
    }

    @Test
    fun `a half-streamed tool call is dropped`() {
        val response = parse(
            toolCallStart("c1", "x"),
            argumentFragment("""{\"a"""),
        )
        assertTrue(response.toolCalls.isEmpty())
    }
}
