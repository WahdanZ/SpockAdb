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
    override val title = "Debug timeline"
    override val description =
        "Spock's Debug Timeline on one clock: lifecycle, process deaths, crashes, app errors, Spock " +
            "actions and MCP calls, to see what came before a bug. Device events need a device and app " +
            "selected in the tool window. Flutter categories (flutter_error, navigation, http; " +
            "flutter_frame in profile builds) come from Spock's session with the selected app run with " +
            "flutter run; flutter_app_status says why they are empty."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        integer("sinceSeconds", "Only the last this many seconds. Default $DEFAULT_SECONDS.", SECONDS)
        val categories = TimelineCategory.entries.map { it.name.lowercase() }
        stringArray("categories", "Only these kinds. Default all.", categories)
        enumeration(
            "minSeverity",
            "Only at least this severe. Default info.",
            TimelineSeverity.entries.map { it.name.lowercase() },
        )
        string("query", "Only events whose title or detail contains this, ignoring case.")
        integer("limit", "At most this many, the latest kept. Default $DEFAULT_LIMIT.", LIMITS)
        boolean("includeDetails", "Include details such as stack traces. Default true.")
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
        val seconds = arguments.optionalInt("sinceSeconds", DEFAULT_SECONDS, SECONDS)
        val limit = arguments.optionalInt("limit", DEFAULT_LIMIT, LIMITS)
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
        val SECONDS = 1..MAX_SECONDS
        val LIMITS = 1..MAX_LIMIT
        const val SWITCHED_OFF = "off — Record device events is switched off in the Timeline tab"
        const val NOT_FOLLOWING = "off — no device and app selected in the Spock ADB tool window, " +
            "or the device's log stream ended (see the latest Device event)"
        val CATEGORY_NAMES = TimelineCategory.entries.joinToString { it.name.lowercase() }
    }
}
