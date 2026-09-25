package spock.adb.diagnostics

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Runs sections and assembles their summaries into one bounded report.
 *
 * The report is the contract, documented in docs/MCP.md and versioned by [SCHEMA_VERSION]:
 *
 * - `likelyProblems` first, ranked, so an agent that reads only the top has the answer.
 * - one object per section, keyed by [DiagnosticSection.id].
 * - `sectionErrors` for sections that failed — a failure is reported in place, never fatal.
 * - `more`: the one tool call per section that returns what the summary left out.
 *
 * Bounded twice. Each section caps its own lists and values, and [MAX_CHARS] is enforced on the
 * whole: past it, the least important sections are replaced by a pointer to their detail tool
 * until the report fits.
 */
class DiagnosticCollector(
    private val budgetNanos: Long = DEFAULT_BUDGET_NANOS,
    private val maxChars: Int = MAX_CHARS,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    fun collect(
        sections: List<DiagnosticSection>,
        probe: DiagnosticProbe,
        preamble: JsonObject = JsonObject(),
    ): JsonObject {
        val deadline = nanoTime() + budgetNanos
        val reports = linkedMapOf<DiagnosticSection, SectionReport>()
        val errors = JsonObject()

        sections.forEach { section ->
            if (nanoTime() > deadline) {
                errors.addProperty(section.id, SKIPPED)
                return@forEach
            }
            runCatching { section.collect(probe) }
                .onSuccess { reports[section] = it }
                .onFailure { failure ->
                    // An agent can act on "the screen is off"; it cannot act on a stack trace.
                    errors.addProperty(
                        section.id,
                        DiagnosticShell.clip(failure.message ?: failure::class.java.simpleName, MAX_ERROR_CHARS),
                    )
                }
        }

        val report = JsonObject()
        report.addProperty("schemaVersion", SCHEMA_VERSION)
        preamble.entrySet().forEach { (key, value) -> report.add(key, value) }
        report.addProperty("packageName", probe.packageName)
        addProblems(report, reports.values.flatMap { it.problems })
        reports.forEach { (section, sectionReport) -> report.add(section.id, sectionReport.data) }
        if (errors.size() > 0) report.add("sectionErrors", errors)
        report.add("more", references(sections, probe))

        return fitToBudget(report, reports.keys.map { it.id })
    }

    private fun addProblems(report: JsonObject, problems: List<LikelyProblem>) {
        val ranked = problems.sortedWith(
            compareBy<LikelyProblem> { it.severity.rank }
                .thenBy { typePriority(it.type) }
                .thenByDescending { it.count },
        )
        report.add(
            "likelyProblems",
            JsonArray().apply { ranked.take(MAX_PROBLEMS).forEach { add(it.toJson()) } },
        )
        if (ranked.size > MAX_PROBLEMS) report.addProperty("moreProblems", ranked.size - MAX_PROBLEMS)
    }

    private fun references(sections: List<DiagnosticSection>, probe: DiagnosticProbe): JsonObject {
        val more = JsonObject()
        sections.forEach { section ->
            val detail = section.detail ?: return@forEach
            val call = JsonObject()
            call.addProperty("tool", detail.tool)
            call.add("arguments", scopedArguments(section, detail, probe.packageName))
            more.add(section.id, call)
        }
        return more
    }

    /** Points the follow-up at the same app, so it describes what this report did. */
    private fun scopedArguments(section: DiagnosticSection, detail: DetailRef, packageName: String?): JsonObject {
        val arguments = detail.arguments.deepCopy()
        if (packageName != null && section.id in APP_SCOPED_DETAILS) {
            arguments.addProperty(if (section.id == AppSection.id) "filter" else "packageName", packageName)
        }
        return arguments
    }

    private fun typePriority(type: String): Int =
        TYPE_PRIORITY.indexOf(type).let { index -> if (index < 0) TYPE_PRIORITY.size else index }

    /** Drops whole sections, least important first, until the report fits. Never truncates JSON. */
    private fun fitToBudget(report: JsonObject, sectionIds: List<String>): JsonObject {
        val omitted = JsonArray()
        for (id in sectionIds.asReversed()) {
            if (render(report).length <= maxChars) break
            report.remove(id)
            omitted.add(id)
        }
        if (omitted.size() > 0) report.add("omittedForSize", omitted)

        val problems = report.getAsJsonArray("likelyProblems")
        while (render(report).length > maxChars && problems != null && problems.size() > 1) {
            problems.remove(problems.size() - 1)
            report.addProperty("moreProblems", (report.get("moreProblems")?.asInt ?: 0) + 1)
        }
        return report
    }

    private fun LikelyProblem.toJson() = JsonObject().apply {
        addProperty("type", type)
        addProperty("severity", severity.id)
        addProperty("summary", summary)
        if (count > 1) addProperty("count", count)
        lastSeen?.let { addProperty("lastSeen", it) }
        section?.let { addProperty("section", it) }
    }

    companion object {
        /**
         * 2: the summary. 1 was the 4.x text bundle, still returned for `format: "full"`. Bump it
         * when a field changes meaning or goes away; adding one does not.
         */
        const val SCHEMA_VERSION = 2

        /** A few thousand tokens: small enough to prepend to every first question. */
        const val MAX_CHARS = 12_000
        const val MAX_PROBLEMS = 10
        private const val MAX_ERROR_CHARS = 300
        private const val SKIPPED = "Skipped: the time budget for this call was spent. Request fewer sections."
        private const val DEFAULT_BUDGET_NANOS = 60_000_000_000L

        /** Among equal severities, what explains the most goes first. */
        private val TYPE_PRIORITY = listOf(
            LogProblemExtractor.TYPE_CRASH,
            LogProblemExtractor.TYPE_ANR,
            "process",
            LogProblemExtractor.TYPE_NETWORK,
            LogProblemExtractor.TYPE_EXCEPTION,
            "screen",
            "deviceCondition",
            "backgroundWork",
            "permission",
        )

        private val APP_SCOPED_DETAILS = setOf(
            AppSection.id,
            LogsSection.id,
            BackgroundWorkSection.id,
            DeviceConditionsSection.id,
            PermissionsSection.id,
        )

        private val GSON = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()

        fun render(report: JsonObject): String = GSON.toJson(report)
    }
}
