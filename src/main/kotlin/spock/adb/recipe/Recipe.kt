package spock.adb.recipe

/**
 * A reusable debugging scenario: an ordered list of Spock tool calls, pauses and checks.
 *
 * Plain data with no Swing and no ddmlib in it, so a recipe can be listed, validated and run by
 * [RecipeRunner] in a unit test as easily as over MCP. Every step names an existing MCP tool
 * rather than carrying its own ADB logic: a recipe composes what Spock can already do, under the
 * same safety levels, so a destructive step still asks the developer before it runs.
 *
 * @property restore steps that put the device back afterwards. They run after [steps] whether
 *   those passed or failed, unless the caller opts out, and a failing one never stops the rest.
 */
data class Recipe(
    /** Stable, snake_case. What `android_run_recipe` takes. */
    val id: String,
    val title: String,
    val description: String,
    val params: List<RecipeParam> = emptyList(),
    val steps: List<RecipeStep>,
    val restore: List<RecipeStep> = emptyList(),
) {
    /** Every tool the recipe can call, in the order it first calls them. */
    val tools: List<String>
        get() = (steps + restore).filterIsInstance<RecipeStep.Call>().map { it.tool }.distinct()
}

/** One named input a recipe takes, referenced from step arguments as `{name}`. */
data class RecipeParam(
    val name: String,
    val description: String,
    val required: Boolean = false,
)

/** What happens to the rest of the recipe when a step fails. */
enum class FailurePolicy {
    /** Skip the remaining steps and go straight to restore. The default. */
    STOP,

    /** Record the failure and carry on, for a step whose result is informational. */
    CONTINUE,
}

sealed interface RecipeStep {
    val title: String
    val onFailure: FailurePolicy

    /** A parameter this step needs. When the caller did not give it, the step is skipped, not failed. */
    val onlyIf: String?

    /**
     * Calls one Spock tool.
     *
     * @property arguments string values are templates: `{name}` is replaced by a recipe parameter
     *   or an earlier step's [captureAs]. An argument that is *only* a reference to a parameter the
     *   caller left out is dropped, so the tool applies its own default — the open project's
     *   package, for one. Any other unresolved reference fails the step.
     * @property captureAs remembers this step's text output under a name later steps can use.
     */
    data class Call(
        override val title: String,
        val tool: String,
        val arguments: Map<String, Any> = emptyMap(),
        val expect: Expectation? = null,
        val captureAs: String? = null,
        /** Keep the images this step returns — a screenshot — in the recipe's result. */
        val keepImages: Boolean = false,
        override val onFailure: FailurePolicy = FailurePolicy.STOP,
        override val onlyIf: String? = null,
    ) : RecipeStep

    /** Waits a fixed time, for a state change nothing on screen announces. Cancellable. */
    data class Pause(
        override val title: String,
        val millis: Long,
        override val onFailure: FailurePolicy = FailurePolicy.STOP,
        override val onlyIf: String? = null,
    ) : RecipeStep
}

/** A check on a step's text output, beyond the tool itself reporting success. */
sealed interface Expectation {
    /** The template, resolved as step arguments are. */
    val template: String

    fun describe(resolved: String): String

    fun matches(output: String, resolved: String): Boolean

    /** The output contains [template], ignoring case. */
    data class Contains(override val template: String) : Expectation {
        override fun describe(resolved: String) = "output contains \"$resolved\""
        override fun matches(output: String, resolved: String) = output.contains(resolved, ignoreCase = true)
    }

    /** The output is exactly [template], after trimming. */
    data class Equals(override val template: String) : Expectation {
        override fun describe(resolved: String) = "output is \"$resolved\""
        override fun matches(output: String, resolved: String) = output.trim() == resolved.trim()
    }
}

/**
 * A step argument sent as a whole number, for a tool that takes one — a job id. Parameters
 * arrive as text, and a tool rightly refuses `"4242"` where it declared an integer.
 */
data class AsInt(val template: String)
