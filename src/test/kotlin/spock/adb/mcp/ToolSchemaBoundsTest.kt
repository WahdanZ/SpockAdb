package spock.adb.mcp

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry

/**
 * Every number an agent can send has a declared range.
 *
 * No schema had one, and the tools clamped or ignored what they were given: `android_get_logcat
 * {"maxLines":-5}` answered "Logcat returned nothing." The schema now carries the range and the
 * reader refuses a value outside it; this keeps a new numeric argument from arriving without one.
 */
class ToolSchemaBoundsTest {

    private fun numericProperties(): List<Triple<String, String, JsonObject>> =
        ToolRegistry.all().flatMap { tool ->
            tool.inputSchema.getAsJsonObject("properties").entrySet()
                .filter { (_, value) -> value.asJsonObject.get("type")?.asString in NUMERIC }
                .map { (name, value) -> Triple(tool.name, name, value.asJsonObject) }
        }

    @Test
    fun `there are numeric arguments to check, so this guard is not passing on nothing`() {
        assertTrue(numericProperties().size >= MINIMUM_EXPECTED, "found ${numericProperties().size}")
    }

    @Test
    fun `every integer and number argument declares both a minimum and a maximum`() {
        val unbounded = numericProperties()
            .filter { (_, _, schema) -> !schema.has("minimum") || !schema.has("maximum") }
            .map { (tool, name, _) -> "$tool.$name" }

        assertTrue(unbounded.isEmpty()) {
            "These numeric arguments have no declared range: $unbounded. Pass bounds to integer(...) or " +
                "number(...), and the same range to the reader, which refuses a value outside it."
        }
    }

    @Test
    fun `every declared range is the right way round`() {
        val inverted = numericProperties()
            .filter { (_, _, schema) -> schema.get("minimum").asDouble > schema.get("maximum").asDouble }
            .map { (tool, name, _) -> "$tool.$name" }

        assertTrue(inverted.isEmpty(), "minimum above maximum: $inverted")
    }

    private companion object {
        val NUMERIC = setOf("integer", "number")

        /** Fewer than this means the scan stopped seeing the schema, not that the tools changed. */
        const val MINIMUM_EXPECTED = 20
    }
}
