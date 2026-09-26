package spock.adb.mcp.tools

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import spock.adb.mcp.McpCall
import spock.adb.mcp.McpServerService
import spock.adb.mcp.ToolGate
import spock.adb.recipe.BuiltInRecipes
import spock.adb.recipe.Phase
import spock.adb.recipe.Recipe
import spock.adb.recipe.RecipeRun
import spock.adb.recipe.RecipeRunner
import spock.adb.recipe.RecipeStep
import spock.adb.recipe.StepStatus

/** `android_list_recipes` — what `android_run_recipe` can run, and what each recipe will do. */
class ListRecipesTool(
    private val recipes: () -> List<Recipe> = { BuiltInRecipes.all },
    private val findTool: (String) -> AdbTool? = ToolRegistry::find,
) : AdbTool {
    override val name = "android_list_recipes"
    override val description =
        "List the Debug Recipes android_run_recipe can run: reusable, multi-step scenarios such as " +
            "testing process death or verifying a deep link. Shows each recipe's parameters, its steps, " +
            "and which steps ask the developer to confirm."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.empty()

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        ToolResult.text(recipes().joinToString("\n\n") { describe(it) })

    private fun describe(recipe: Recipe): String = buildString {
        appendLine("${recipe.id} — ${recipe.title}")
        appendLine("  ${recipe.description}")
        if (recipe.params.isEmpty()) {
            appendLine("  Parameters: none")
        } else {
            appendLine("  Parameters:")
            recipe.params.forEach {
                val required = if (it.required) " (required)" else ""
                appendLine("    ${it.name}$required: ${it.description}")
            }
        }
        appendLine("  Steps:")
        recipe.steps.forEachIndexed { index, step -> appendLine("    ${index + 1}. ${line(step)}") }
        if (recipe.restore.isNotEmpty()) {
            appendLine("  Restore (runs even after a failure):")
            recipe.restore.forEachIndexed { index, step -> appendLine("    R${index + 1}. ${line(step)}") }
        }
    }.trimEnd()

    private fun line(step: RecipeStep): String = when (step) {
        is RecipeStep.Pause -> "${step.title} (wait ${step.millis} ms)"
        is RecipeStep.Call -> buildString {
            append("${step.title} [${step.tool}]")
            if (findTool(step.tool)?.safety == ToolSafety.DESTRUCTIVE) append(" — asks the developer to confirm")
            step.onlyIf?.let { append(" — only when $it is given") }
        }
    }
}

/**
 * `android_run_recipe` — runs a Debug Recipe and reports every step.
 *
 * Each step is an ordinary call to another tool, made the way the MCP server makes one: through
 * the same gate, against the same context, recorded in the same activity history under the client
 * `recipe:<id>`. A step whose tool is switched off is refused, not run, and a destructive step asks
 * the developer exactly as it would if an agent had called it directly. That is why this tool can
 * be a safe action: it adds sequencing, never privilege.
 */
class RunRecipeTool(
    private val recipes: () -> List<Recipe> = { BuiltInRecipes.all },
    private val findTool: (String) -> AdbTool? = ToolRegistry::find,
    private val isToolEnabled: (String) -> Boolean = ::enabledInSettings,
    private val record: (McpCall) -> Unit = ::recordInHistory,
) : AdbTool {
    override val name = "android_run_recipe"
    override val description =
        "Run a Debug Recipe — an ordered sequence of Spock tool calls, waits and checks — and report " +
            "each step as passed, failed, skipped or not run. A failing step stops the recipe unless it is " +
            "marked to continue; restore steps run afterwards regardless. Destructive steps still ask the " +
            "developer. Call android_list_recipes first to see the recipes and their parameters."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        enumeration("recipe", "The recipe to run.", recipes().map { it.id }, required = true)
        stringMap("params", "The recipe's parameters, by name. android_list_recipes lists them.")
        boolean("restore", "Run the recipe's restore steps at the end. Defaults to true.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // Taken first, on this thread: the default signal is this thread's interrupt.
        val signal = context.cancellationSignal()
        val id = arguments.requiredString("recipe")
        val recipe = recipes().firstOrNull { it.id == id }
            ?: return ToolResult.error("No recipe is called '$id'. Available: ${recipes().joinToString { it.id }}.")
        val params = arguments.params()
        val serial = arguments.optionalString("deviceSerial")

        val runner = RecipeRunner(
            invoke = { tool, stepArguments ->
                if (serial != null && !stepArguments.has("deviceSerial")) {
                    stepArguments.addProperty("deviceSerial", serial)
                }
                invokeStep(recipe, tool, stepArguments, context)
            },
            pause = { millis -> pause(millis, signal::isCancelled) },
            isCancelled = signal::isCancelled,
        )
        val problems = runner.validate(recipe, params)
        if (problems.isNotEmpty()) return ToolResult.error("Recipe '$id' was not run. ${problems.joinToString(" ")}")

        val run = runner.run(recipe, params, restore = arguments.optionalBoolean("restore", default = true))
        return ToolResult(listOf(ToolContent.Text(RecipeReport.format(run))) + run.images, isError = !run.passed)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun invokeStep(recipe: Recipe, toolName: String, arguments: JsonObject, context: ToolContext): ToolResult {
        // A recipe that runs a recipe could loop, and would hide what it runs from the list.
        require(toolName !in RECIPE_TOOLS) { "A recipe cannot run $toolName." }
        val tool = findTool(toolName) ?: error("Recipe '${recipe.id}' names an unknown tool, $toolName.")

        val startedAt = System.currentTimeMillis()
        val result = if (!isToolEnabled(toolName)) {
            ToolResult.error(ToolGate.refusal(toolName))
        } else {
            try {
                tool.execute(arguments, context)
            } catch (e: Exception) {
                ToolResult.error(e.message ?: "${e.javaClass.simpleName} while running $toolName")
            }
        }

        record(
            McpCall(
                toolName = toolName,
                safety = tool.safety,
                arguments = arguments.toString(),
                result = result.content.joinToString("\n") { item ->
                    when (item) {
                        is ToolContent.Text -> item.text
                        is ToolContent.Image -> "[${item.mimeType}, ${item.base64Data.length} base64 chars]"
                    }
                }.take(RESULT_PREVIEW_CHARS),
                durationMs = System.currentTimeMillis() - startedAt,
                isError = result.isError,
                client = "$CLIENT_PREFIX${recipe.id}",
                deviceSerial = arguments.optionalString("deviceSerial"),
            ),
        )
        return result
    }

    private fun JsonObject.params(): Map<String, String> {
        val node = get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return node.entrySet()
            .filter { (_, value) -> value.isJsonPrimitive }
            .associate { (key, value) -> key to value.asString }
    }

    companion object {
        /** The Activity tab's client column for a step, followed by the recipe id. */
        const val CLIENT_PREFIX = "recipe:"
        private val RECIPE_TOOLS = setOf("android_run_recipe", "android_list_recipes")
        private const val RESULT_PREVIEW_CHARS = 4_000
        private const val PAUSE_SLICE_MS = 100L

        /** Sleeps in slices so a cancel is noticed within one. False when cancelled. */
        internal fun pause(millis: Long, isCancelled: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < deadline) {
                if (isCancelled()) return false
                try {
                    Thread.sleep(minOf(PAUSE_SLICE_MS, deadline - System.currentTimeMillis()).coerceAtLeast(1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return !isCancelled()
        }

        // Without an application — a unit test — there are no settings to consult and no history
        // to write to; everything is enabled and nothing is recorded.
        private fun enabledInSettings(tool: String): Boolean =
            ApplicationManager.getApplication()?.let { McpServerService.getInstance().isToolEnabled(tool) } ?: true

        private fun recordInHistory(call: McpCall) {
            ApplicationManager.getApplication()?.let { McpServerService.getInstance().record(call) }
        }
    }
}

/** How a [RecipeRun] reads to an agent: a verdict first, then one line per step. */
internal object RecipeReport {

    private const val DETAIL_CHARS = 400

    fun format(run: RecipeRun): String = buildString {
        val steps = run.outcomes.filter { it.phase == Phase.STEPS }
        val counts = StepStatus.entries.mapNotNull { status ->
            val count = steps.count { it.status == status }
            if (count > 0) "$count ${status.name.lowercase().replace('_', ' ')}" else null
        }
        val verdict = when {
            run.cancelled -> "CANCELLED"
            run.passed -> "PASSED"
            else -> "FAILED"
        }
        appendLine("Recipe ${run.recipe.id} (${run.recipe.title}): $verdict — ${counts.joinToString()}.")

        steps.forEach { appendLine(line("${it.index + 1}", it)) }
        val restore = run.outcomes.filter { it.phase == Phase.RESTORE }
        if (restore.isNotEmpty()) {
            appendLine(if (run.restoreSkipped) "Restore (skipped on request):" else "Restore:")
            restore.forEach { appendLine(line("R${it.index + 1}", it)) }
        }
        if (run.images.isNotEmpty()) append("${run.images.size} capture(s) follow, in step order.")
    }.trimEnd()

    private fun line(label: String, outcome: spock.adb.recipe.StepOutcome): String {
        val step = outcome.step
        val tool = (step as? RecipeStep.Call)?.tool?.let { " [$it]" }.orEmpty()
        val ran = outcome.status == StepStatus.PASSED || outcome.status == StepStatus.FAILED
        val time = if (ran) " ${outcome.durationMs} ms" else ""
        val detail = outcome.detail.replace(Regex("\\s+"), " ").trim().let {
            if (it.length > DETAIL_CHARS) it.take(DETAIL_CHARS) + "…" else it
        }
        return "$label. ${outcome.status.name.replace('_', ' ')} ${step.title}$tool$time — $detail"
    }
}
