package spock.adb.recipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ToolRegistry

/**
 * The shipped recipes against the tools they call. A renamed tool or argument would otherwise
 * surface only when a developer ran the recipe on a device.
 */
class BuiltInRecipesTest {

    private val reference = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")

    @Test
    fun `at least three recipes ship, with unique snake_case ids`() {
        val ids = BuiltInRecipes.all.map { it.id }
        assertTrue(ids.size >= 3)
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it.matches(Regex("[a-z][a-z0-9_]*")), it) }
    }

    @Test
    fun `every step calls a registered tool with arguments it declares`() {
        BuiltInRecipes.all.forEach { recipe ->
            (recipe.steps + recipe.restore).filterIsInstance<RecipeStep.Call>().forEach { step ->
                val tool = ToolRegistry.find(step.tool)
                    ?: error("${recipe.id}: '${step.title}' calls unknown tool ${step.tool}")
                val declared = tool.inputSchema.getAsJsonObject("properties").keySet()
                val undeclared = step.arguments.keys - declared
                assertTrue(undeclared.isEmpty(), "${recipe.id}: ${step.tool} does not take $undeclared")

                val required = tool.inputSchema.getAsJsonArray("required").map { it.asString }
                val missing = required - step.arguments.keys
                assertTrue(missing.isEmpty(), "${recipe.id}: ${step.tool} needs $missing")
            }
        }
    }

    @Test
    fun `every reference is a parameter or an earlier capture`() {
        BuiltInRecipes.all.forEach { recipe ->
            val known = recipe.params.map { it.name }.toMutableSet()
            (recipe.steps + recipe.restore).forEach { step ->
                step.onlyIf?.let { assertTrue(it in known, "${recipe.id}: onlyIf '$it' is not a parameter") }
                step.after?.let { title ->
                    assertTrue(recipe.steps.any { it.title == title }, "${recipe.id}: after '$title' is not a step")
                }
                references(step).forEach {
                    assertTrue(it in known, "${recipe.id}: '${step.title}' refers to unknown '$it'")
                }
                (step as? RecipeStep.Call)?.captureAs?.let { known += it }
            }
        }
    }

    private fun references(step: RecipeStep): List<String> {
        if (step !is RecipeStep.Call) return emptyList()
        val templates = step.arguments.values.mapNotNull { (it as? String) ?: (it as? AsInt)?.template } +
            listOfNotNull(step.expect?.template)
        return templates.flatMap { template -> reference.findAll(template).map { it.groupValues[1] }.toList() }
    }

    @Test
    fun `no recipe runs a recipe`() {
        BuiltInRecipes.all.forEach { recipe ->
            assertTrue(recipe.tools.none { "recipe" in it }, recipe.id)
        }
    }
}
