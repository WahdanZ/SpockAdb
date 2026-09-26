package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.timeline.DebugTimeline
import spock.adb.timeline.DebugTimelineService
import spock.adb.timeline.TimelineCategory
import spock.adb.timeline.TimelineExport
import spock.adb.timeline.TimelineFilter
import spock.adb.timeline.TimelineSeverity

/**
 * `android_get_debug_timeline` — what happened, in order, before the agent asked.
 *
 * Reads what the IDE already recorded; it asks the device nothing. That is its value and its
 * limit: it answers "what happened just before the bug" for events that were recorded, and the
 * device's own events are only recorded while the Spock ADB tool window has a device and an app
 * selected. The result says which it was recording, so an empty answer is not mistaken for a
 * quiet app.
 */
class GetDebugTimelineTool : AdbTool {
    override val name = DebugTimelineService.GET_TIMELINE_TOOL
    override val description =
        "Read Spock's Debug Timeline: one chronological list of what happened recently — the app's " +
            "activity lifecycle and fragments, process starts and deaths, crashes and ANRs, warnings and " +
            "errors the app logged, actions run from the Spock tool window (restart, clear data, permission " +
            "changes, storage writes, jobs run, device conditions), devices connecting, markers the developer " +
            "added, and MCP tool calls. All times are on one clock. Use it to answer what happened " +
            "immediately before a bug, or to see what a sequence of your own calls did to the app. Reads what " +
            "the IDE already recorded and asks the device nothing; device events are recorded only while the " +
            "Spock ADB tool window has a device and app selected, which the result reports."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        integer("sinceSeconds", "Only events from the last this many seconds. Defaults to 300.")
        stringArray(
            "categories",
            "Only these kinds of event. Defaults to all.",
            TimelineCategory.entries.map { it.name.lowercase() },
        )
        enumeration(
            "minSeverity",
            "Only events at least this severe. Defaults to info.",
            TimelineSeverity.entries.map { it.name.lowercase() },
        )
        string("query", "Only events whose title or detail contains this text, ignoring case.")
        integer("limit", "At most this many events, the most recent kept. Defaults to 200.")
        boolean("includeDetails", "Include each event's detail, such as stack traces. Defaults to true.")
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val service = DebugTimelineService.getInstance(context.requireProject())
        val recording = service.recordingTarget
            ?: if (service.recordingDevice) NOT_FOLLOWING else SWITCHED_OFF
        return answer(service.timeline, recording, arguments)
    }

    internal fun answer(
        timeline: DebugTimeline,
        /** What device events come from now, or why none do. */
        recording: String,
        arguments: JsonObject,
        nowMs: Long = System.currentTimeMillis(),
    ): ToolResult {
        val categories = arguments.optionalStringList("categories")?.map { text ->
            TimelineCategory.parse(text)
                ?: return ToolResult.error("Unknown category \"$text\". Use: $CATEGORY_NAMES.")
        }
        val severity = arguments.optionalString("minSeverity")?.let { text ->
            TimelineSeverity.parse(text)
                ?: return ToolResult.error("Unknown minSeverity \"$text\". Use info, warning or error.")
        }
        val seconds = arguments.optionalInt("sinceSeconds", DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
        val limit = arguments.optionalInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val details = arguments.optionalBoolean("includeDetails", true)
        val filter = TimelineFilter(
            categories = categories?.toSet() ?: TimelineCategory.entries.toSet(),
            minSeverity = severity ?: TimelineSeverity.INFO,
            query = arguments.optionalString("query").orEmpty(),
            sinceMs = nowMs - seconds * MILLIS,
        )
        val matching = timeline.query(filter)
        val shown = matching.takeLast(
            limit
        ).let { events -> if (details) events else events.map { it.copy(detail = "") } }

        return ToolResult.text(
            buildString {
                append("Device recording: ")
                append(recording)
                append(".\n")
                append(matching.size).append(" event(s) in the last ").append(seconds).append("s")
                if (shown.size < matching.size) append(", the latest ").append(shown.size).append(" shown")
                if (timeline.dropped > 0) {
                    append("; ").append(timeline.dropped).append(" older event(s) were dropped to stay within ")
                    append(timeline.capacity)
                }
                append(".\n\n")
                append(TimelineExport.format(shown))
            },
        )
    }

    private companion object {
        const val MILLIS = 1_000L
        const val DEFAULT_SECONDS = 300
        const val MAX_SECONDS = 86_400
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 2_000
        const val SWITCHED_OFF = "off — Record device events is switched off in the Timeline tab"
        const val NOT_FOLLOWING = "off — no device and app selected in the Spock ADB tool window, " +
            "or the device's log stream ended (see the latest Device event)"
        val CATEGORY_NAMES = TimelineCategory.entries.joinToString { it.name.lowercase() }
    }
}
