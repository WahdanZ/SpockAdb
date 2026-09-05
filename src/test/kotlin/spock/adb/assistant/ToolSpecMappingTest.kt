package spock.adb.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry

/**
 * The assistant and the MCP transports must describe the same tools.
 *
 * The point of `toToolSpec` is that a tool is defined once. A test that only checked the
 * mapping compiled would not catch the failure that matters: the registry growing a tool the
 * assistant never hears about.
 */
class ToolSpecMappingTest {

    @Test
    fun `every registered tool is offered to the model, and none is invented`() {
        val specs = ToolRegistry.all().toToolSpecs()

        assertEquals(ToolRegistry.all().map { it.name }, specs.map { it.name })
    }

    @Test
    fun `each spec carries what a model needs to call it`() {
        ToolRegistry.all().toToolSpecs().forEach { spec ->
            assertTrue(spec.name.isNotBlank(), "a tool with no name cannot be called")
            assertTrue(
                spec.description.isNotBlank(),
                "${spec.name} has no description, so a model can only guess when to use it",
            )
            assertEquals(
                "object",
                spec.inputSchema.get("type")?.asString,
                "${spec.name}'s schema must be an object, which is what both providers accept",
            )
        }
    }

    @Test
    fun `the schema handed to the model is the registry's own, not a copy of it`() {
        // Same instance, so a schema can never be edited in one place and stale in the other.
        val tool = ToolRegistry.all().first()

        assertTrue(tool.inputSchema === tool.toToolSpec().inputSchema)
    }
}
