package spock.adb.diagnostics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * A [DiagnosticCollector] report read back for a person: the ranked problems, then one line per
 * section, in the order the report lists them.
 *
 * Read from the report rather than from the sections, so the Diagnose tab shows exactly what
 * "Copy for AI" copies and what `android_diagnose_current_screen` returns — a second path from
 * the device to the screen would be a second place for them to disagree.
 */
class ScreenDiagnosis(val report: JsonObject) {

    data class Problem(val severity: String, val summary: String, val count: Int)

    /** Ranked as the collector ranked them. */
    val problems: List<Problem> = report.array("likelyProblems")?.mapNotNull { element ->
        val problem = element as? JsonObject ?: return@mapNotNull null
        Problem(
            severity = problem.string("severity") ?: "info",
            summary = problem.string("summary") ?: return@mapNotNull null,
            count = problem.int("count") ?: 1,
        )
    }.orEmpty()

    /** Problems the collector dropped to stay within its size budget. */
    val moreProblems: Int = report.int("moreProblems") ?: 0

    private val screen = report.obj(ScreenSection.id)

    /** The resumed activity's fully qualified class, for navigating to its source. */
    val activityClass: String? = screen?.string("component")?.substringAfter('/')

    val hasFragments: Boolean = (screen?.array("fragments")?.size() ?: 0) > 0

    /** One line per section that came back, labelled for a person. */
    val facts: List<Pair<String, String>> = buildList {
        screen?.let { add("Screen" to describeScreen(it)) }
        report.obj(AppSection.id)?.let { add("Process" to describeApp(it)) }
        report.obj(LogsSection.id)?.let { add("Logs" to describeLogs(it)) }
        report.obj(UiSection.id)?.let { add("UI" to describeUi(it)) }
        report.obj(PermissionsSection.id)?.let { add("Permissions" to describePermissions(it)) }
        report.obj(BackgroundWorkSection.id)?.let { add("Background work" to describeWork(it)) }
        report.obj(DeviceConditionsSection.id)?.let { add("Device" to describeConditions(it)) }
    }

    /** Sections that failed, with why — shown, never swallowed. */
    val sectionErrors: List<Pair<String, String>> =
        report.obj("sectionErrors")?.entrySet()?.map { (id, why) -> id to why.asStringOrEmpty() }.orEmpty()

    /** The report as an AI assistant should receive it: said what it is, then the JSON. */
    fun forAi(): String =
        "Spock ADB diagnosis of the current Android screen (schema ${report.int("schemaVersion")}). " +
            "likelyProblems is ranked; each entry under \"more\" is the MCP tool call that returns " +
            "a section's raw data.\n\n```json\n${DiagnosticCollector.render(report)}\n```"

    private fun describeScreen(screen: JsonObject): String {
        val activity = screen.string("activity") ?: return "No activity is resumed"
        return buildList {
            add(activity + if (screen.bool("appInForeground") == false) " (another app)" else "")
            screen.array("fragments")?.takeIf { it.size() > 0 }
                ?.let { add("fragments: " + it.joinText { name -> name.trim() }) }
            screen.array("activityStack")?.takeIf { it.size() > 1 }
                ?.let { add("stack, top first: " + it.joinText()) }
        }.joinToString(" · ")
    }

    private fun describeApp(app: JsonObject): String {
        app.string("note")?.let { return it }
        return when (app.bool("running")) {
            true -> "running, pid " + app.array("pids").joinText()
            else -> "not running"
        }
    }

    private fun describeLogs(logs: JsonObject): String =
        "${logs.int("errors") ?: 0} error(s), ${logs.int("warnings") ?: 0} warning(s) in the last " +
            "${logs.int("windowLines") ?: 0} lines; ${logs.int("appLines") ?: 0} from the app"

    private fun describeUi(ui: JsonObject): String {
        val a11y = ui.obj("accessibility")
        return "${ui.string("framework") ?: "unknown framework"}, ${ui.int("visibleNodes") ?: 0} visible " +
            "node(s), ${ui.int("interactive") ?: 0} interactive; accessibility: " +
            "${a11y?.int("errors") ?: 0} error(s), ${a11y?.int("warnings") ?: 0} warning(s)"
    }

    private fun describePermissions(permissions: JsonObject): String {
        val denied = permissions.array("denied")
        val total = permissions.int("runtime") ?: 0
        return when {
            total == 0 -> "no runtime permissions"
            denied == null || denied.size() == 0 -> "all $total runtime permission(s) granted"
            else -> "${permissions.int("granted") ?: 0} of $total granted; denied: " + denied.joinText()
        }
    }

    private fun describeWork(work: JsonObject): String {
        work.string("jobsProblem")?.let { return it }
        return "${work.int("jobs") ?: 0} job(s), ${work.int("running") ?: 0} running, " +
            "${work.int("failing") ?: 0} failing; ${work.int("alarms")?.toString() ?: "unknown"} alarm(s)"
    }

    private fun describeConditions(conditions: JsonObject): String = buildList {
        add(if (conditions.bool("dozing") == true) "in Doze" else "not dozing")
        conditions.string("standbyBucket")?.let { add("bucket $it") }
        conditions.int("batteryLevel")?.let { add("battery $it%") }
        conditions.bool("charging")?.let { add(if (it) "charging" else "on battery") }
        if (conditions.bool("batteryOverridden") == true) add("battery overridden")
    }.joinToString(", ")

    private companion object {
        fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
        fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString
        fun JsonObject.int(name: String): Int? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        fun JsonObject.bool(name: String): Boolean? =
            get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        fun JsonArray?.joinText(separator: String = ", ", transform: (String) -> String = { it }): String =
            this?.mapNotNull { element -> element.asStringOrEmpty().takeIf { it.isNotBlank() }?.let(transform) }
                ?.joinToString(separator)
                .orEmpty()

        fun JsonElement.asStringOrEmpty(): String = if (isJsonPrimitive) asString else toString()
    }
}
