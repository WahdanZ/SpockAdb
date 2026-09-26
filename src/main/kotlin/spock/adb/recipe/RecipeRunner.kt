package spock.adb.recipe

import com.google.gson.JsonObject
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolResult

/**
 * Runs a [Recipe] step by step, and reports what each step did.
 *
 * Knows nothing about ddmlib, the IDE or MCP: each step reaches the device through [invoke],
 * which the caller wires to the tool registry with its own gate, context and audit trail. That
 * is what keeps the runner testable without a device, and a recipe from being a way around a
 * tool the developer switched off.
 *
 * @param invoke runs one tool with fully resolved arguments. May throw; a throw fails the step.
 * @param pause waits, returning false when the wait was cancelled.
 * @param isCancelled checked before every step; once true, no further step of the recipe starts.
 */
class RecipeRunner(
    private val invoke: (tool: String, arguments: JsonObject) -> ToolResult,
    private val pause: (millis: Long) -> Boolean,
    private val isCancelled: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Problems with [params] for [recipe], empty when it can run. Checked before anything touches
     * the device, so a typo in a parameter name fails fast instead of half-way through.
     */
    fun validate(recipe: Recipe, params: Map<String, String>): List<String> {
        val known = recipe.params.map { it.name }.toSet()
        val missing = recipe.params.filter { it.required && params[it.name].isNullOrBlank() }.map { it.name }
        val unknown = params.keys - known
        return buildList {
            if (missing.isNotEmpty()) add("Missing required parameter(s): ${missing.joinToString()}.")
            if (unknown.isNotEmpty()) {
                add(
                    "Unknown parameter(s): ${unknown.joinToString()}. " +
                        "'${recipe.id}' takes: ${known.joinToString().ifEmpty { "none" }}.",
                )
            }
        }
    }

    /**
     * Runs every step in order, then the restore steps unless [restore] is false.
     *
     * @throws IllegalArgumentException when [validate] finds a problem; nothing has run.
     */
    fun run(recipe: Recipe, params: Map<String, String>, restore: Boolean = true): RecipeRun {
        val problems = validate(recipe, params)
        require(problems.isEmpty()) { problems.joinToString(" ") }

        val values = params.filterValues { it.isNotBlank() }.toMutableMap()
        val outcomes = mutableListOf<StepOutcome>()
        var stopped = false
        var cancelled = false

        recipe.steps.forEachIndexed { index, step ->
            if (!cancelled && isCancelled()) cancelled = true
            val outcome = when {
                stopped || cancelled -> StepOutcome.notRun(Phase.STEPS, index, step)
                else -> runStep(Phase.STEPS, index, step, values)
            }
            outcomes += outcome
            if (outcome.status == StepStatus.FAILED && step.onFailure == FailurePolicy.STOP) stopped = true
        }

        // Restore runs even after a failure or a cancel: that is exactly when a device is most
        // likely to have been left in forced Doze or unplugged. Every restore step is attempted.
        recipe.restore.forEachIndexed { index, step ->
            outcomes += when {
                restore -> runStep(Phase.RESTORE, index, step, values)
                else -> StepOutcome.notRun(Phase.RESTORE, index, step)
            }
        }
        return RecipeRun(recipe, outcomes, cancelled, restoreSkipped = !restore && recipe.restore.isNotEmpty())
    }

    private fun runStep(phase: Phase, index: Int, step: RecipeStep, values: MutableMap<String, String>): StepOutcome {
        step.onlyIf?.takeIf { values[it].isNullOrBlank() }?.let { needed ->
            return StepOutcome(phase, index, step, StepStatus.SKIPPED, "Skipped: '$needed' was not given.", 0)
        }
        val startedAt = clock()
        val (status, detail, images) = when (step) {
            is RecipeStep.Pause -> when {
                pause(step.millis) -> Verdict(StepStatus.PASSED, "Waited ${step.millis} ms.")
                else -> Verdict(StepStatus.FAILED, "Cancelled while waiting.")
            }
            is RecipeStep.Call -> call(step, values)
        }
        return StepOutcome(phase, index, step, status, detail, clock() - startedAt, images)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun call(step: RecipeStep.Call, values: MutableMap<String, String>): Verdict = try {
        val result = invoke(step.tool, Templates.arguments(step.arguments, values))
        val text = result.content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }
        val images = if (step.keepImages) result.content.filterIsInstance<ToolContent.Image>() else emptyList()
        val failure = when {
            result.isError -> text.ifBlank { "${step.tool} reported an error." }
            else -> unmet(step, text, values)
        }
        when (failure) {
            null -> {
                step.captureAs?.let { values[it] = text.trim() }
                Verdict(StepStatus.PASSED, text.ifBlank { describeImages(images) }, images)
            }
            else -> Verdict(StepStatus.FAILED, failure, images)
        }
    } catch (e: Exception) {
        Verdict(StepStatus.FAILED, e.message ?: "${e.javaClass.simpleName} while running ${step.tool}")
    }

    /** Why [text] does not meet the step's expectation, or null when it does or there is none to check. */
    private fun unmet(step: RecipeStep.Call, text: String, values: Map<String, String>): String? {
        val expectation = step.expect ?: return null
        if (Templates.isUnsetReference(expectation.template, values)) return null
        val wanted = Templates.resolve(expectation.template, values)
        if (expectation.matches(text, wanted)) return null
        return "Expected ${expectation.describe(wanted)}, got \"${text.trim()}\"."
    }

    private fun describeImages(images: List<ToolContent.Image>) =
        if (images.isEmpty()) "Done." else "Captured ${images.size} image(s)."

    private data class Verdict(
        val status: StepStatus,
        val detail: String,
        val images: List<ToolContent.Image> = emptyList(),
    )
}

enum class Phase { STEPS, RESTORE }

enum class StepStatus { PASSED, FAILED, SKIPPED, NOT_RUN }

data class StepOutcome(
    val phase: Phase,
    /** Zero-based within its [phase]. */
    val index: Int,
    val step: RecipeStep,
    val status: StepStatus,
    val detail: String,
    val durationMs: Long,
    val images: List<ToolContent.Image> = emptyList(),
) {
    companion object {
        fun notRun(phase: Phase, index: Int, step: RecipeStep) =
            StepOutcome(phase, index, step, StepStatus.NOT_RUN, "Not run.", 0)
    }
}

/** Everything one run of a recipe did. */
data class RecipeRun(
    val recipe: Recipe,
    val outcomes: List<StepOutcome>,
    val cancelled: Boolean,
    /** The caller asked not to restore, and the recipe had restore steps. */
    val restoreSkipped: Boolean = false,
) {
    /** No step failed, restore included, and nothing was cancelled. */
    val passed: Boolean get() = !cancelled && outcomes.none { it.status == StepStatus.FAILED }

    val images: List<ToolContent.Image> get() = outcomes.flatMap { it.images }
}

/** `{name}` substitution for step arguments and expectations. */
internal object Templates {

    private val reference = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")

    fun resolve(template: String, values: Map<String, String>): String =
        reference.replace(template) { match ->
            val name = match.groupValues[1]
            values[name] ?: throw IllegalArgumentException(
                "Step needs '$name', which was neither given nor captured by an earlier step.",
            )
        }

    /**
     * Whether [template] is exactly `{name}` for a value nobody gave. Such an argument is left
     * out, and such an expectation is not checked: the caller chose not to say.
     */
    fun isUnsetReference(template: String, values: Map<String, String>): Boolean {
        val whole = reference.matchEntire(template) ?: return false
        return values[whole.groupValues[1]] == null
    }

    /**
     * Resolves every argument. One that is exactly `{name}` for a value nobody gave is left out,
     * so the tool falls back to its own default rather than receiving an empty string.
     */
    fun arguments(arguments: Map<String, Any>, values: Map<String, String>): JsonObject = JsonObject().apply {
        arguments.forEach { (key, value) ->
            val template = (value as? String) ?: (value as? AsInt)?.template
            if (template != null && isUnsetReference(template, values)) return@forEach
            when (value) {
                is String -> addProperty(key, resolve(value, values))
                is AsInt -> addProperty(key, wholeNumber(key, resolve(value.template, values)))
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> throw IllegalArgumentException(
                    "Unsupported argument type for '$key': ${value.javaClass.simpleName}",
                )
            }
        }
    }

    private fun wholeNumber(key: String, text: String): Int =
        text.trim().toIntOrNull() ?: throw IllegalArgumentException("'$key' must be a whole number, got \"$text\".")
}
