package spock.adb.recipe

import spock.adb.recipe.RecipeStep.Call
import spock.adb.recipe.RecipeStep.Pause

/**
 * The recipes that ship with the plugin.
 *
 * Each one is a scenario developers otherwise click through by hand, written as calls to tools
 * that already exist. Tool names are checked against the registry by a test, so renaming a tool
 * cannot quietly break a recipe.
 */
object BuiltInRecipes {

    /** Enough of the app's log to show the job ran, not so much it buries it. */
    private const val LOGCAT_LINES = 40

    private const val FORCE_DOZE = "Force the device into deep Doze"

    private val packageName = RecipeParam(
        "packageName",
        "The app under test. Defaults to the open project's application ID.",
    )

    private val expectText = RecipeParam(
        "expectText",
        "Text that should be on screen afterwards, such as a value the screen must keep.",
    )

    private fun screenshot(title: String) = Call(
        title,
        "android_take_screenshot",
        keepImages = true,
        onFailure = FailurePolicy.CONTINUE,
    )

    private fun waitForText(title: String, timeoutMs: Int, onlyIf: String? = "expectText") = Call(
        title,
        "android_wait_for_element",
        mapOf("text" to "{expectText}", "until" to "visible", "timeoutMs" to timeoutMs),
        onlyIf = onlyIf,
    )

    val processDeath = Recipe(
        id = "process_death",
        title = "Test process death",
        description = "Kill the app's process in the background the way Android does to reclaim memory, " +
            "relaunch it, and check the same screen came back — with expectText still on it, when given. " +
            "Run it with the screen under test in front.",
        params = listOf(packageName, expectText),
        steps = listOf(
            Call("Note the screen in front", "android_get_current_activity", captureAs = "activityBefore"),
            waitForText("expectText is on screen before the kill", timeoutMs = 5_000),
            screenshot("Capture: before"),
            Call(
                "Kill the process in the background and relaunch",
                "android_simulate_process_death",
                mapOf("packageName" to "{packageName}"),
            ),
            Pause("Let the app recreate its screen", millis = 1_500),
            Call(
                "The same screen came back",
                "android_get_current_activity",
                expect = Expectation.Equals("{activityBefore}"),
            ),
            waitForText("expectText survived the process death", timeoutMs = 10_000),
            screenshot("Capture: after"),
        ),
    )

    val deepLink = Recipe(
        id = "deep_link",
        title = "Open a deep link and verify the destination",
        description = "Open a URI, then check which activity it landed on and that expected text is showing.",
        params = listOf(
            RecipeParam("uri", "The URI to open, e.g. myapp://product/42.", required = true),
            packageName,
            RecipeParam("expectActivity", "All or part of the activity class that should open, e.g. ProductActivity."),
            expectText,
        ),
        steps = listOf(
            Call(
                "Open the deep link",
                "android_open_deep_link",
                mapOf("uri" to "{uri}", "packageName" to "{packageName}"),
            ),
            waitForText("expectText is on screen", timeoutMs = 10_000),
            Pause("Let the destination settle", millis = 1_000),
            Call(
                "The destination is expectActivity",
                "android_get_current_activity",
                expect = Expectation.Contains("{expectActivity}"),
            ),
            screenshot("Capture: destination"),
        ),
    )

    val restartKeepsState = Recipe(
        id = "restart_keeps_state",
        title = "Restart the app and verify persisted state",
        description = "Check expectText is on screen, force-stop and relaunch the app, and check it is shown " +
            "again. A restart discards saved instance state, so this passes only for state the app persists " +
            "itself — preferences, a database, a server. Run it with that screen in front.",
        params = listOf(expectText.copy(required = true), packageName),
        steps = listOf(
            waitForText("expectText is on screen before the restart", timeoutMs = 5_000, onlyIf = null),
            screenshot("Capture: before"),
            Call("Force-stop and relaunch", "android_restart_app", mapOf("packageName" to "{packageName}")),
            waitForText("expectText is back after the restart", timeoutMs = 15_000, onlyIf = null),
            screenshot("Capture: after"),
        ),
    )

    val jobInDoze = Recipe(
        id = "job_in_doze",
        title = "Run a background job in Doze",
        description = "Force the device into deep Doze, run one of the app's scheduled jobs now, then take " +
            "the device out of Doze again. Forcing Doze affects every app, so the developer is asked to " +
            "confirm it. Once Doze is forced, the restore step runs even when a later step fails or the run " +
            "is cancelled.",
        params = listOf(
            RecipeParam("jobId", "The job to run, as android_get_scheduled_jobs reports it.", required = true),
            RecipeParam("namespace", "The job's namespace, if it has one. Found automatically when omitted."),
            packageName,
        ),
        steps = listOf(
            Call(
                "The job is scheduled",
                "android_get_scheduled_jobs",
                mapOf("packageName" to "{packageName}"),
                expect = Expectation.ContainsWord("Job {jobId}"),
            ),
            Call(FORCE_DOZE, "android_force_doze"),
            Call(
                "Run the job now",
                "android_run_job_now",
                mapOf("jobId" to AsInt("{jobId}"), "namespace" to "{namespace}", "packageName" to "{packageName}"),
            ),
            Pause("Give the job time to run", millis = 3_000),
            Call(
                "What the app logged",
                "android_get_logcat",
                mapOf("packageName" to "{packageName}", "maxLines" to LOGCAT_LINES),
                onFailure = FailurePolicy.CONTINUE,
            ),
            Call(
                "The job's state afterwards",
                "android_get_scheduled_jobs",
                mapOf("packageName" to "{packageName}"),
                onFailure = FailurePolicy.CONTINUE,
            ),
        ),
        restore = listOf(
            Call(
                "Take the device out of Doze",
                "android_reset_device_conditions",
                mapOf("packageName" to "{packageName}"),
                after = FORCE_DOZE,
            ),
        ),
    )

    val all: List<Recipe> = listOf(processDeath, deepLink, restartKeepsState, jobInDoze)

    fun find(id: String): Recipe? = all.firstOrNull { it.id == id }
}
