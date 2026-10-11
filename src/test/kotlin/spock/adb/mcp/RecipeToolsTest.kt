package spock.adb.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.ListRecipesTool
import spock.adb.mcp.tools.RunRecipeTool
import spock.adb.mcp.tools.Schema
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.ToolResult
import spock.adb.mcp.tools.ToolSafety
import spock.adb.mcp.tools.confirmDestructive
import spock.adb.recipe.BuiltInRecipes
import spock.adb.recipe.Recipe
import spock.adb.recipe.RecipeParam
import spock.adb.recipe.RecipeStep.Call

/** `android_list_recipes` and `android_run_recipe`: sequencing that adds no privilege. */
class RecipeToolsTest {

    private val recorded = mutableListOf<McpCall>()
    private val executed = mutableListOf<Pair<String, JsonObject>>()

    private inner class FakeTool(
        override val name: String,
        override val safety: ToolSafety = ToolSafety.SAFE_ACTION,
        private val reply: (ToolContext) -> ToolResult = { ToolResult.text("done: $name") },
    ) : AdbTool {
        override val description = name
        override val inputSchema: JsonObject = Schema.empty()
        override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            executed += name to arguments
            return reply(context)
        }
    }

    private val tools = listOf(
        FakeTool("android_get_current_activity", ToolSafety.READ_ONLY) { ToolResult.text("com.example.Main") },
        FakeTool("android_open_deep_link"),
        FakeTool("android_take_screenshot", ToolSafety.READ_ONLY) { ToolResult.image("iVBOR") },
        FakeTool("android_force_doze", ToolSafety.DESTRUCTIVE) { context ->
            if (context.confirmDestructive("android_force_doze", "doze", context.requireDevice())) {
                ToolResult.text("dozing")
            } else {
                ToolResult.error("The developer declined to force Doze.")
            }
        },
        FakeTool("android_reset_device_conditions"),
    ).associateBy { it.name }

    private val recipe = Recipe(
        id = "demo",
        title = "Demo",
        description = "A demo.",
        params = listOf(RecipeParam("uri", "", required = true)),
        steps = listOf(
            Call("open", "android_open_deep_link", mapOf("uri" to "{uri}")),
            Call("where", "android_get_current_activity"),
            Call("see", "android_take_screenshot", keepImages = true),
        ),
    )

    private fun tool(recipes: List<Recipe> = listOf(recipe), enabled: (String) -> Boolean = { true }) = RunRecipeTool(
        recipes = { recipes },
        findTool = tools::get,
        isToolEnabled = enabled,
        record = { recorded += it },
    )

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `runs every step, reports each, and returns the captures`() {
        val result = tool().execute(args("""{"recipe":"demo","params":{"uri":"app://x"}}"""), FakeToolContext())

        assertFalse(result.isError)
        val text = (result.content.first() as ToolContent.Text).text
        assertTrue(text.startsWith("Recipe demo (Demo): PASSED — 3 passed."), text)
        assertTrue("1. PASSED open [android_open_deep_link]" in text, text)
        assertEquals(1, result.content.count { it is ToolContent.Image })
        assertEquals("app://x", executed.first().second["uri"].asString)
    }

    @Test
    fun `each step is recorded in the activity history under the recipe`() {
        val arguments = args("""{"recipe":"demo","params":{"uri":"app://x"},"deviceSerial":"emulator-5554"}""")
        tool().execute(arguments, FakeToolContext())

        assertEquals(
            listOf("android_open_deep_link", "android_get_current_activity", "android_take_screenshot"),
            recorded.map { it.toolName },
        )
        assertTrue(recorded.all { it.client == "recipe:demo" && it.deviceSerial == "emulator-5554" })
        assertTrue("base64" in recorded.last().result)
    }

    @Test
    fun `a disabled tool is refused, recorded, and stops the recipe`() {
        val result = tool(enabled = { it != "android_get_current_activity" })
            .execute(args("""{"recipe":"demo","params":{"uri":"app://x"}}"""), FakeToolContext())

        assertTrue(result.isError)
        assertEquals(listOf("android_open_deep_link"), executed.map { it.first })
        assertTrue(recorded[1].isError)
        assertTrue("disabled" in recorded[1].result)
    }

    @Test
    fun `a destructive step still asks, and a refusal fails the recipe but restores`() {
        val doze = Recipe(
            id = "doze",
            title = "Doze",
            description = "",
            steps = listOf(Call("doze", "android_force_doze"), Call("where", "android_get_current_activity")),
            restore = listOf(Call("reset", "android_reset_device_conditions")),
        )
        val context = FakeToolContext(confirmationAnswer = false)

        val result = tool(listOf(doze)).execute(args("""{"recipe":"doze"}"""), context)

        assertTrue(result.isError)
        assertEquals(listOf("android_force_doze"), context.confirmations)
        assertEquals(listOf("android_force_doze", "android_reset_device_conditions"), executed.map { it.first })
    }

    @Test
    fun `a cancel by interrupt stops the steps but not the restore`() {
        // The stdio server cancels by interrupting the worker thread; ddmlib then fails every shell
        // call on it. These tools stand in for that: the first is where the cancel lands, and the
        // restore refuses to run on an interrupted thread, as a real ADB call would.
        val cancelled = FakeTool("android_open_deep_link") {
            Thread.currentThread().interrupt()
            ToolResult.text("opened")
        }
        val reset = FakeTool("android_reset_device_conditions") {
            when {
                Thread.currentThread().isInterrupted -> ToolResult.error("ClosedByInterruptException")
                else -> ToolResult.text("reset")
            }
        }
        val lookup = tools + mapOf(cancelled.name to cancelled, reset.name to reset)
        val interrupted = Recipe(
            id = "interrupted",
            title = "Interrupted",
            description = "",
            steps = listOf(Call("open", "android_open_deep_link"), Call("where", "android_get_current_activity")),
            restore = listOf(Call("reset", "android_reset_device_conditions")),
        )

        val result = RunRecipeTool({ listOf(interrupted) }, lookup::get, { true }, { recorded += it })
            .execute(args("""{"recipe":"interrupted"}"""), FakeToolContext())

        // Put back for the caller, and cleared here so the test thread is left as it was found.
        assertTrue(Thread.interrupted(), "the interrupt must be restored after restore")
        assertTrue(result.isError)
        assertEquals(listOf("android_open_deep_link", "android_reset_device_conditions"), executed.map { it.first })
        assertFalse(recorded.last().isError, recorded.last().result)
    }

    @Test
    fun `bad input is refused before any step runs`() {
        val unknown = tool().execute(args("""{"recipe":"nope"}"""), FakeToolContext())
        val missing = tool().execute(args("""{"recipe":"demo"}"""), FakeToolContext())

        assertTrue(unknown.isError && missing.isError)
        assertTrue("uri" in (missing.content.single() as ToolContent.Text).text)
        assertTrue(executed.isEmpty() && recorded.isEmpty())
    }

    @Test
    fun `a recipe cannot run a recipe`() {
        val nested = Recipe("nested", "Nested", "", steps = listOf(Call("again", "android_run_recipe")))

        val result = tool(listOf(nested)).execute(args("""{"recipe":"nested"}"""), FakeToolContext())

        assertTrue(result.isError)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `the list names every recipe, its parameters and its confirmed steps`() {
        val result = ListRecipesTool().execute(JsonObject(), FakeToolContext())
        val text = (result.content.single() as ToolContent.Text).text

        BuiltInRecipes.all.forEach { assertTrue(it.id in text, it.id) }
        assertTrue("uri (required)" in text)
        assertTrue("[android_force_doze] — asks the developer to confirm" in text, text)
    }

    @Test
    fun `the schema offers exactly the shipped recipes`() {
        val offered = RunRecipeTool().inputSchema
            .getAsJsonObject("properties").getAsJsonObject("recipe").getAsJsonArray("enum").map { it.asString }

        assertEquals(BuiltInRecipes.all.map { it.id }, offered)
    }

    @Test
    fun `a pause ends early when cancelled`() {
        val started = System.currentTimeMillis()
        assertFalse(RunRecipeTool.pause(5_000) { System.currentTimeMillis() - started > 150 })
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertTrue(RunRecipeTool.pause(20) { false })
    }
}
