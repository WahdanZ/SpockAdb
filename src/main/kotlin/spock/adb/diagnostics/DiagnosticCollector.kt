package spock.adb.diagnostics

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.analysis.FlutterProblemTypes
import java.util.IdentityHashMap

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

    fun <P : DiagnosticProbe> collect(
        sections: List<DiagnosticSection<P>>,
        probe: P,
        preamble: JsonObject = JsonObject(),
    ): JsonObject {
        val deadline = nanoTime() + budgetNanos
        val reports = linkedMapOf<DiagnosticSection<P>, SectionReport>()
        val errors = JsonObject()
        val applicable = sections.filter { it.appliesTo(probe) }

        applicable.forEach { section ->
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
        val all = reports.values.flatMap { it.problems }
        val listed = addProblems(report, all, companionsOf(reports.values, all))
        reports.forEach { (section, sectionReport) -> report.add(section.id, sectionReport.data) }
        if (errors.size() > 0) report.add("sectionErrors", errors)
        report.add("more", references(applicable, probe))
        afterRanking(reports, listed)

        return fitToBudget(report, reports.keys.map { it.id })
    }

    /**
     * Ranks [problems], each of [companions] right after the problem it belongs with unless it
     * already ranks higher, lists the first [MAX_PROBLEMS] with their ids, and returns them as listed.
     */
    private fun addProblems(
        report: JsonObject,
        problems: List<LikelyProblem>,
        companions: Map<LikelyProblem, List<LikelyProblem>>,
    ): List<Pair<String, LikelyProblem>> {
        val ranked = withCompanions(
            problems.sortedWith(
                compareBy<LikelyProblem> { it.severity.rank }
                    .thenBy { typePriority(it.type) }
                    .thenByDescending { it.count },
            ),
            companions,
        )
        val listed = ranked.take(MAX_PROBLEMS).mapIndexed { index, problem -> "p${index + 1}" to problem }
        report.add(
            "likelyProblems",
            JsonArray().apply { listed.forEach { (id, problem) -> add(problem.toJson(id)) } },
        )
        if (ranked.size > MAX_PROBLEMS) report.addProperty("moreProblems", ranked.size - MAX_PROBLEMS)
        return listed
    }

    /** Every section's [SectionReport.companions], by problem identity; one that fails adds none. */
    private fun companionsOf(
        reports: Collection<SectionReport>,
        all: List<LikelyProblem>,
    ): Map<LikelyProblem, List<LikelyProblem>> {
        val found = IdentityHashMap<LikelyProblem, List<LikelyProblem>>()
        reports.forEach { report ->
            val step = report.companions ?: return@forEach
            runCatching { step(all) }
                .onSuccess { found.putAll(it) }
                .onFailure { log.warn("A section's pairing of problems failed", it) }
        }
        return found
    }

    /** [ranked], with each companion moved up to just after the first problem it belongs with. */
    private fun withCompanions(
        ranked: List<LikelyProblem>,
        companions: Map<LikelyProblem, List<LikelyProblem>>,
    ): List<LikelyProblem> {
        if (companions.isEmpty()) return ranked
        val rank = IdentityHashMap<LikelyProblem, Int>().apply { ranked.forEachIndexed { i, p -> put(p, i) } }
        val moved = IdentityHashMap<LikelyProblem, LikelyProblem>()
        ranked.forEach { anchor ->
            companions[anchor].orEmpty().forEach { companion ->
                val below = (rank[companion] ?: return@forEach) > (rank[anchor] ?: return@forEach)
                if (below && companion !in moved) moved[companion] = anchor
            }
        }
        return ranked.flatMap { problem ->
            if (problem in moved) {
                emptyList()
            } else {
                listOf(problem) + companions[problem].orEmpty().filter { moved[it] === problem }
            }
        }
    }

    /** Each section's [SectionReport.afterRanking]; one that fails adds nothing and costs nothing else. */
    private fun afterRanking(
        reports: Map<out DiagnosticSection<*>, SectionReport>,
        listed: List<Pair<String, LikelyProblem>>,
    ) {
        val sections = reports.entries.associate { (section, report) -> section.id to report.data }
        val ranked = RankedProblems(listed, sections)
        reports.values.forEach { report ->
            report.afterRanking?.let { step ->
                runCatching { step(ranked) }.onFailure { log.warn("A section's step after ranking failed", it) }
            }
        }
    }

    private fun references(sections: List<DiagnosticSection<*>>, probe: DiagnosticProbe): JsonObject {
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
    private fun scopedArguments(section: DiagnosticSection<*>, detail: DetailRef, packageName: String?): JsonObject {
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

    private fun LikelyProblem.toJson(id: String) = JsonObject().apply {
        addProperty("id", id)
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
            LogProblemExtractor.TYPE_FLUTTER_PLUGIN,
            FlutterProblemTypes.FLUTTER_ERROR,
            LogProblemExtractor.TYPE_NETWORK,
            LogProblemExtractor.TYPE_EXCEPTION,
            "screen",
            FlutterProblemTypes.JANK,
            FlutterProblemTypes.FREQUENT_REBUILDS,
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

        private val log = Logger.getInstance(DiagnosticCollector::class.java)

        private val GSON = GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create()

        fun render(report: JsonObject): String = GSON.toJson(report)
    }
}
