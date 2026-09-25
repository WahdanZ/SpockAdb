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
interface DiagnosticSection {

    /** Stable key in the output and in `include`. Clients bind to it. */
    val id: String

    /**
     * Where to get the raw data this section summarises. The summary never embeds it; an agent
     * that needs it makes this one call instead.
     */
    val detail: DetailRef?

    /**
     * Reads and summarises. May throw: the collector reports the failure in place of this
     * section and carries on with the rest.
     */
    fun collect(probe: DiagnosticProbe): SectionReport
}

/** What a section hands back: bounded data, and the problems it noticed. */
data class SectionReport(
    val data: JsonObject,
    val problems: List<LikelyProblem> = emptyList(),
)

/** The tool call that returns a section's raw data. */
data class DetailRef(val tool: String, val arguments: JsonObject = JsonObject())

/**
 * What every section reads from: one device, and the app the question is about.
 *
 * @param packageName null when no app is known — no project and none given. Sections that are
 *   about an app then say so rather than guessing one.
 */
class DiagnosticProbe(
    val device: IDevice,
    val serialNumber: String,
    val packageName: String?,
    val logWindowLines: Int = DEFAULT_LOG_WINDOW_LINES,
) {
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
) {
    enum class Severity(val id: String, val rank: Int) {
        ERROR("error", 0),
        WARNING("warning", 1),
        INFO("info", 2),
    }
}
