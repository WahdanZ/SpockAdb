package spock.adb.mcp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry

/**
 * A call that leaves out a required argument must be told so.
 *
 * Each tool reads its arguments in whatever order suits it, so one that resolves an element
 * or queries the device first reports *that* step failing rather than the argument the caller
 * omitted. `android_input_text_into_element` did exactly this: called without `value`, it
 * answered "No element matched testTag='…' and text='…'", which points an agent at the screen
 * when the real problem is its own call — and it folds the text-to-type into the message as
 * though it were part of the selector.
 *
 * The check therefore lives in [McpProtocol], before the tool runs, so it holds for every
 * tool in the registry rather than the ones whose authors happened to read arguments first.
 */
class RequiredArgumentsTest {

    private val context = FakeToolContext()
    private val calls = mutableListOf<McpCall>()
    private val protocol = McpProtocol(contextProvider = { context }, auditLog = { calls += it })

    private fun call(tool: String, arguments: String) =
        JsonParser.parseString(
            protocol.handle(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call",
                   "params":{"name":"$tool","arguments":$arguments}}""",
            ),
        ).asJsonObject.getAsJsonObject("result")

    private fun errorText(tool: String, arguments: String): String {
        val result = call(tool, arguments)
        assertTrue(result.get("isError").asBoolean, "$tool should have failed: $result")
        return result.getAsJsonArray("content").toString()
    }

    @Test
    fun `omitting the text to type names value, not the element`() {
        // The exact call that used to mislead: a selector, but no value.
        val text = errorText("android_input_text_into_element", """{"testTag":"search_field"}""")

        assertTrue(text.contains("value"), "should name the missing argument: $text")
        assertFalse(text.contains("No element matched"), "should not blame the element: $text")
    }

    @Test
    fun `every missing argument is listed at once`() {
        // Two round-trips to learn about two omissions is one too many.
        val text = errorText("android_tap", "{}")

        assertTrue(text.contains("x") && text.contains("y"), "should name both: $text")
        assertTrue(text.contains("arguments"), "should be plural: $text")
    }

    @Test
    fun `a blank string counts as missing, matching requiredString`() {
        val text = errorText("android_open_deep_link", """{"uri":"   "}""")

        assertTrue(text.contains("uri"), "should name uri: $text")
    }

    @Test
    fun `zero is a value, not an omission`() {
        // The check must not confuse "absent" with "falsy": tapping (0, 0) is a real request.
        val result = call("android_tap", """{"x":0,"y":0}""")

        assertFalse(
            result.getAsJsonArray("content").toString().contains("missing required"),
            "0 should be accepted as a coordinate: $result",
        )
    }

    @Test
    fun `a tool with no required arguments is unaffected`() {
        val result = call("android_list_devices", "{}")

        assertFalse(result.get("isError").asBoolean, "should succeed: $result")
    }

    @Test
    fun `a rejected call is still audited`() {
        // A malformed call is exactly the kind a developer wants to see in the activity trail.
        errorText("android_tap", "{}")

        assertEquals(listOf("android_tap"), calls.map { it.toolName })
        assertTrue(calls.single().isError, "the audit entry should record the failure")
    }

    @Test
    fun `every tool declaring a required argument is checked`() {
        // Guards the mechanism rather than one tool: if the schema stopped emitting `required`
        // the checks above would pass vacuously while every tool went unvalidated.
        val withRequired = ToolRegistry.all().filter {
            it.inputSchema.getAsJsonArray("required")?.isEmpty == false
        }

        assertTrue(
            withRequired.size >= EXPECTED_MINIMUM,
            "only ${withRequired.size} tools declare required arguments, which looks like the " +
                "schema stopped emitting them",
        )
    }

    private companion object {
        /** Well below the true count, so adding or removing a tool does not break this. */
        const val EXPECTED_MINIMUM = 8
    }
}
