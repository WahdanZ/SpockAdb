package spock.adb.diagnostics

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject

/**
 * One part of the diagnostic context: what it reads, how it summarises it, and which problems it
 * can see.
 *
 * Adding a section is implementing this and listing it in [DiagnosticSections.ALL]. Nothing here
 * knows about the tool window or about MCP: a section reads the device and returns data, and the
 * caller decides how to show it. That is what lets the tool, the Assistant and anything later
 * share one set of sections without dragging Swing into a device read.
 */
interface DiagnosticSection<in P : DiagnosticProbe> {

    /** Stable key in the output and in `include`. Clients bind to it. */
    val id: String

    /**
     * Where to get the raw data this section summarises. The summary never embeds it; an agent
     * that needs it makes this one call instead.
     */
    val detail: DetailRef?

    /**
     * Whether this section has anything to say about [probe] at all. One that has not — the
     * `flutter` section for an app that is not a Flutter app — is left out of the report, rather
     * than reported empty. Cheap: no device reads.
     */
    fun appliesTo(probe: P): Boolean = true

    /**
     * Reads and summarises. May throw: the collector reports the failure in place of this
     * section and carries on with the rest.
     */
    fun collect(probe: P): SectionReport
}

/**
 * What a section hands back: bounded data, and the problems it noticed.
 *
 * [companions] runs before ranking, with every problem of every section: for each of this
 * section's problems, the others' that belong with it — the log lines around a Flutter error. The
 * ranking lists each right after its problem, so it makes the list whenever its problem does,
 * however low it would rank alone; one that already ranks higher stays where it is.
 *
 * [afterRanking] runs once every section is in and the problems are ranked and given their ids,
 * before the size cut: a section that relates its findings to another's adds that to its own
 * [data] there. A failure in either costs only that step.
 */
data class SectionReport(
    val data: JsonObject,
    val problems: List<LikelyProblem> = emptyList(),
    val afterRanking: ((RankedProblems) -> Unit)? = null,
    val companions: ((List<LikelyProblem>) -> Map<LikelyProblem, List<LikelyProblem>>)? = null,
)

/**
 * The report's problems as listed, best first, each with the `id` the report gives it, and each
 * section's data by section id. A problem ranked below the cut has no id.
 */
class RankedProblems(val listed: List<Pair<String, LikelyProblem>>, val sections: Map<String, JsonObject>) {
    /** The id of exactly [problem] — the same object a section reported — or null when it was not listed. */
    fun idOf(problem: LikelyProblem): String? = listed.firstOrNull { it.second === problem }?.first
}

/** The tool call that returns a section's raw data. */
data class DetailRef(val tool: String, val arguments: JsonObject = JsonObject())

/**
 * What a diagnosis is about: one target, and the app the question is about.
 *
 * Each platform has its own probe, because what a section reads differs — `dumpsys` and logcat
 * on Android, `simctl` on the iOS simulator — and a section declares which one it needs. The
 * collector only needs the app.
 */
interface DiagnosticProbe {
    /** Null when no app is known — no project and none given. Sections then say so. */
    val packageName: String?
}

/**
 * An Android device, read over ADB.
 *
 * @param packageName null when no app is known — no project and none given. Sections that are
 *   about an app then say so rather than guessing one.
 */
class AndroidProbe(
    val device: IDevice,
    val serialNumber: String,
    override val packageName: String?,
    val logWindowLines: Int = DEFAULT_LOG_WINDOW_LINES,
    /**
     * The app's Flutter session, as [FlutterSection] reports it; null for an app that is not a
     * Flutter app, or when nobody looked — and then there is no `flutter` section.
     */
    val flutter: FlutterDiagnosticSource? = null,
) : DiagnosticProbe {
    /**
     * Process ids of [packageName], read once and shared: the app section reports them and the
     * log section filters by them, and two reads a moment apart could disagree.
     */
    val pids: List<String> by lazy {
        val name = packageName ?: return@lazy emptyList()
        DiagnosticShell.run(device, "pidof ${spock.adb.ShellQuote.quote(name)}")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    /** One bounded logcat snapshot shared by Logs and Flutter, so correlation describes one window. */
    val logText: String by lazy {
        DiagnosticShell.run(device, "logcat -d -v threadtime -t $logWindowLines *:W")
    }

    /** The ordinary log problems parsed once from [logText]. */
    val logProblems: LogProblemExtractor.Result by lazy {
        LogProblemExtractor.extract(logText, packageName, pids)
    }

    companion object {
        const val DEFAULT_LOG_WINDOW_LINES = 1_500
    }
}

/** A problem worth an agent's attention, stated in one line. */
data class LikelyProblem(
    /** `crash`, `anr`, `network`, `exception`, `log`, `accessibility`, `backgroundWork`, … */
    val type: String,
    val severity: Severity,
    val summary: String,
    /** How many times it was seen, for problems read from a stream. */
    val count: Int = 1,
    /** Device clock time it was last seen, as logcat printed it, when known. */
    val lastSeen: String? = null,
    /** The section that reported it, so the agent knows where to look for more. */
    val section: String? = null,
    /**
     * When each occurrence was seen, as logcat printed it, oldest first and bounded — for pairing
     * it with what happened around any of them. Not in the report: [lastSeen] is.
     */
    val seenAt: List<String> = listOfNotNull(lastSeen),
    /**
     * Per-layer problems this problem combines. They stay available to sections as evidence, but
     * are left out of the top-level ranking so one incident is not reported as several faults.
     */
    val replaces: List<LikelyProblem> = emptyList(),
) {
    enum class Severity(val id: String, val rank: Int) {
        ERROR("error", 0),
        WARNING("warning", 1),
        INFO("info", 2),
    }
}
