package spock.adb.recipe

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.mcp.tools.ToolResult
import spock.adb.recipe.RecipeStep.Call
import spock.adb.recipe.RecipeStep.Pause

class RecipeRunnerTest {

    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private val replies = mutableMapOf<String, ToolResult>()

    private fun runner(cancelAfter: Int = Int.MAX_VALUE, pauseCompletes: Boolean = true) = RecipeRunner(
        invoke = { tool, arguments ->
            calls += tool to arguments
            replies[tool] ?: ToolResult.text("ok from $tool")
        },
        pause = { pauseCompletes },
        isCancelled = { calls.size >= cancelAfter },
        clock = { 0L },
    )

    private fun recipe(
        vararg steps: RecipeStep,
        restore: List<RecipeStep> = emptyList(),
        params: List<RecipeParam> = emptyList(),
    ) =
        Recipe("test", "Test", "A test recipe.", params, steps.toList(), restore)

    @Test
    fun `runs every step in order and passes`() {
        val run = runner().run(recipe(Call("one", "tool_a"), Pause("wait", 10), Call("two", "tool_b")), emptyMap())

        assertTrue(run.passed)
        assertEquals(listOf("tool_a", "tool_b"), calls.map { it.first })
        assertEquals(List(3) { StepStatus.PASSED }, run.outcomes.map { it.status })
    }

    @Test
    fun `a failed step stops the rest but restore still runs`() {
        replies["tool_a"] = ToolResult.error("boom")
        val run = runner().run(
            recipe(Call("one", "tool_a"), Call("two", "tool_b"), restore = listOf(Call("undo", "tool_reset"))),
            emptyMap(),
        )

        assertFalse(run.passed)
        assertEquals(listOf("tool_a", "tool_reset"), calls.map { it.first })
        assertEquals(
            listOf(StepStatus.FAILED, StepStatus.NOT_RUN, StepStatus.PASSED),
            run.outcomes.map { it.status },
        )
        assertEquals("boom", run.outcomes.first().detail)
    }

    @Test
    fun `a step marked continue does not stop the recipe but still fails it`() {
        replies["tool_a"] = ToolResult.error("informational")
        val run = runner().run(
            recipe(Call("one", "tool_a", onFailure = FailurePolicy.CONTINUE), Call("two", "tool_b")),
            emptyMap(),
        )

        assertEquals(listOf("tool_a", "tool_b"), calls.map { it.first })
        assertFalse(run.passed)
    }

    @Test
    fun `a restore step only undoes a step that passed`() {
        replies["tool_change"] = ToolResult.error("declined")
        val run = runner().run(
            recipe(
                Call("change", "tool_change"),
                restore = listOf(Call("undo", "tool_reset", after = "change"), Call("always", "tool_other")),
            ),
            emptyMap(),
        )

        assertEquals(listOf("tool_change", "tool_other"), calls.map { it.first })
        assertEquals(StepStatus.SKIPPED, run.outcomes[1].status)
    }

    @Test
    fun `restore runs inside the caller's restore wrapper`() {
        var wrapped = false
        val runner = RecipeRunner(
            invoke = { tool, _ ->
                calls += tool to JsonObject()
                ToolResult.text(if (wrapped) "inside" else "outside")
            },
            pause = { true },
            aroundRestore = { block ->
                wrapped = true
                block()
                wrapped = false
            },
        )

        val run = runner.run(recipe(Call("one", "tool_a"), restore = listOf(Call("undo", "tool_reset"))), emptyMap())

        assertEquals(listOf("outside", "inside"), run.outcomes.map { it.detail })
    }

    @Test
    fun `a whole-word expectation does not match a longer number`() {
        val expectation = Expectation.ContainsWord("Job 4")

        assertFalse(expectation.matches("Job 42 — Service\nJob 4545 — Service", "Job 4"))
        assertTrue(expectation.matches("Job 42 — Service\nJob 4 (namespace x) — Service", "Job 4"))
    }

    @Test
    fun `restore can be skipped on request`() {
        val recipe = recipe(Call("one", "tool_a"), restore = listOf(Call("undo", "tool_reset")))
        val run = runner().run(recipe, emptyMap(), restore = false)

        assertEquals(listOf("tool_a"), calls.map { it.first })
        assertTrue(run.restoreSkipped)
        assertEquals(StepStatus.NOT_RUN, run.outcomes.last().status)
    }

    @Test
    fun `parameters and captures fill in arguments and expectations`() {
        replies["get"] = ToolResult.text("  com.example.MainActivity\n")
        val run = runner().run(
            recipe(
                Call("remember", "get", captureAs = "before"),
                Call(
                    "use",
                    "act",
                    mapOf("uri" to "app://{id}/x", "count" to 3, "flag" to true, "job" to AsInt("{id}")),
                ),
                Call("check", "get", expect = Expectation.Equals("{before}")),
                params = listOf(RecipeParam("id", "", required = true)),
            ),
            mapOf("id" to "42"),
        )

        assertTrue(run.passed, run.outcomes.toString())
        val arguments = calls[1].second
        assertEquals("app://42/x", arguments["uri"].asString)
        assertEquals(3, arguments["count"].asInt)
        assertTrue(arguments["flag"].asBoolean)
        assertTrue(arguments["job"].asJsonPrimitive.isNumber)
        assertEquals(42, arguments["job"].asInt)
    }

    @Test
    fun `an unmet expectation fails the step with what was expected and what came back`() {
        replies["get"] = ToolResult.text("com.example.OtherActivity")
        val run = runner().run(
            recipe(
                Call("check", "get", expect = Expectation.Contains("{want}")),
                params = listOf(RecipeParam("want", "")),
            ),
            mapOf("want" to "DetailActivity"),
        )

        assertFalse(run.passed)
        val detail = run.outcomes.single().detail
        assertTrue("DetailActivity" in detail && "OtherActivity" in detail, detail)
    }

    @Test
    fun `an argument or expectation that is only an omitted parameter is left out`() {
        val run = runner().run(
            recipe(
                Call("act", "act", mapOf("packageName" to "{pkg}"), expect = Expectation.Contains("{want}")),
                params = listOf(RecipeParam("pkg", ""), RecipeParam("want", "")),
            ),
            emptyMap(),
        )

        assertTrue(run.passed)
        assertFalse(calls.single().second.has("packageName"))
    }

    @Test
    fun `a step that needs an omitted parameter is skipped, not failed`() {
        val run = runner().run(
            recipe(Call("maybe", "tool_a", onlyIf = "text"), params = listOf(RecipeParam("text", ""))),
            emptyMap(),
        )

        assertTrue(run.passed)
        assertTrue(calls.isEmpty())
        assertEquals(StepStatus.SKIPPED, run.outcomes.single().status)
    }

    @Test
    fun `an embedded reference to a missing value fails the step`() {
        val run = runner().run(recipe(Call("use", "act", mapOf("uri" to "app://{nothing}"))), emptyMap())

        assertFalse(run.passed)
        assertTrue("nothing" in run.outcomes.single().detail)
    }

    @Test
    fun `a throwing tool fails its step rather than the run`() {
        val run = RecipeRunner(invoke = { _, _ -> error("device went away") }, pause = { true })
            .run(recipe(Call("one", "tool_a")), emptyMap())

        assertEquals(StepStatus.FAILED, run.outcomes.single().status)
        assertEquals("device went away", run.outcomes.single().detail)
    }

    @Test
    fun `cancelling stops further steps, still restores, and is not a pass`() {
        val run = runner(cancelAfter = 1).run(
            recipe(Call("one", "tool_a"), Call("two", "tool_b"), restore = listOf(Call("undo", "tool_reset"))),
            emptyMap(),
        )

        assertTrue(run.cancelled)
        assertFalse(run.passed)
        assertEquals(listOf("tool_a", "tool_reset"), calls.map { it.first })
    }

    @Test
    fun `a cancelled pause fails its step`() {
        val run = runner(pauseCompletes = false).run(recipe(Pause("wait", 1_000), Call("after", "tool_a")), emptyMap())

        assertEquals(listOf(StepStatus.FAILED, StepStatus.NOT_RUN), run.outcomes.map { it.status })
    }

    @Test
    fun `missing and unknown parameters are refused before anything runs`() {
        val recipe = recipe(Call("one", "tool_a"), params = listOf(RecipeParam("uri", "", required = true)))

        val problems = runner().validate(recipe, mapOf("url" to "x"))
        assertEquals(2, problems.size)
        assertThrows<IllegalArgumentException> { runner().run(recipe, mapOf("url" to "x")) }
        assertTrue(calls.isEmpty())
    }
}
