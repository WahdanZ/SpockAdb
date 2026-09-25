package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.device.ops.RecompositionException
import spock.adb.device.ops.RecompositionOperations
import spock.adb.uitree.RecompositionCounts
import spock.adb.uitree.UiCaptureException

/**
 * `android_get_recomposition_counts` — how often each composable ran while the agent watched.
 *
 * A safe action rather than read-only: it turns composition tracing on in the app's process,
 * where it stays until the process ends. Tracing changes nothing the app does.
 */
class GetRecompositionCountsTool : AdbTool {
    override val name = "android_get_recomposition_counts"
    override val description =
        "Record a running Jetpack Compose app for a few seconds and report how many times each " +
            "composable composed or recomposed, with its source file and line, most frequent first. " +
            "Use it to find composables that recompose more than the screen's changes explain. " +
            "Reproduce the interaction while it records, or record an idle screen to find recompositions " +
            "that should not happen at all. The app must include androidx.compose.runtime:runtime-tracing " +
            "and androidx.tracing:tracing-perfetto-binary (debug builds are enough); if it does not, the " +
            "result says what to add. Counts are Compose's own trace slices, not estimates. They cannot " +
            "be matched to nodes of android_get_ui_tree, which carries no composable names. A high count " +
            "is a lead, not proof of a performance problem."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "App to record. Defaults to the open project's application ID.")
        integer(
            "durationSeconds",
            "How long to record, 1 to 30 seconds. Defaults to 5. The call blocks for this long.",
        )
        boolean(
            "includeLibraries",
            "Also list composables from androidx and Kotlin libraries, such as Text and Box. Defaults " +
                "to false: the app's own composables are what it can change.",
        )
        integer("limit", "At most this many composables. Defaults to 30.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        val seconds = arguments.optionalInt("durationSeconds", DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)
        val includeLibraries = arguments.optionalBoolean("includeLibraries", false)
        val limit = arguments.optionalInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val counts = try {
            RecompositionOperations(device.device, device.serialNumber, context.cancellationSignal())
                .record(packageName, seconds * MILLIS_PER_SECOND)
        } catch (e: RecompositionException) {
            return ToolResult.error(e.message.orEmpty())
        } catch (e: UiCaptureException) {
            val advice = if (e.kind == UiCaptureException.Kind.DEVICE_UNAVAILABLE) {
                " Call android_list_devices to see which devices are connected."
            } else {
                ""
            }
            return ToolResult.error(e.message.orEmpty() + advice)
        }
        return ToolResult.text(render(counts, packageName, seconds, includeLibraries, limit))
    }

    internal fun render(
        counts: RecompositionCounts,
        packageName: String,
        seconds: Int,
        includeLibraries: Boolean,
        limit: Int,
    ): String = buildString {
        val shown = if (includeLibraries) counts.composables else counts.appOnly()
        append("Recorded ").append(packageName).append(" for ").append(seconds).append("s: ")
        append(counts.total).append(" composition(s) across ").append(counts.composables.size)
        append(" composable(s)")
        if (!includeLibraries) {
            append(", ").append(shown.size).append(" of them the app's own")
        }
        append(".\n")
        if (counts.composables.isEmpty()) {
            append(
                "Nothing composed while recording. The screen may have been idle, which is what an idle " +
                    "screen should do; interact with it while recording to measure a change.",
            )
            return@buildString
        }
        if (shown.isEmpty()) {
            append("Only library composables ran. Pass includeLibraries=true to list them.")
            return@buildString
        }
        append("Count is times composed or recomposed; the first composition of anything that appeared ")
        append("during the recording counts once.\n\n")
        shown.take(limit).forEach {
            append(it.count.toString().padStart(COUNT_WIDTH)).append("  ").append(it.name)
            append("  (").append(it.location).append(")\n")
        }
        if (shown.size > limit) {
            append("[").append(shown.size - limit).append(" more below the limit of ").append(limit).append(".]")
        }
    }.trimEnd()

    private companion object {
        const val DEFAULT_SECONDS = 5
        const val MAX_SECONDS = 30
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 500
        const val COUNT_WIDTH = 6
        const val MILLIS_PER_SECOND = 1_000L
    }
}
