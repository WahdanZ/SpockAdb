package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.CancellationSignal
import spock.adb.device.ConnectedDevice
import spock.adb.device.ops.RecompositionException
import spock.adb.device.ops.RecompositionOperations
import spock.adb.flutter.FlutterBuildCache
import spock.adb.flutter.FlutterFollowerService
import spock.adb.flutter.FlutterRebuildRecorder
import spock.adb.flutter.analysis.shortSourcePath
import spock.adb.uitree.RecompositionCounts
import spock.adb.uitree.UiCaptureException
import java.util.Locale

/**
 * `android_get_recomposition_counts` — how often each composable ran while the agent watched, or,
 * for a Flutter app, how often each widget was built.
 *
 * A safe action rather than read-only: it turns composition tracing on in the app's process,
 * where it stays until the process ends. Tracing changes nothing the app does. For a Flutter app
 * it switches the inspector's rebuild tracking on for the window, only if it was off, and off
 * again afterwards ([FlutterRebuildRecorder]).
 *
 * @param flutterRebuilds the rebuild window for a Flutter app, or null for any other app, which
 *   then records Compose. The project's Flutter session by default; tests pass their own.
 */
class GetRecompositionCountsTool(
    private val flutterRebuilds: (FlutterRebuildRequest) -> FlutterRebuildRecorder.Result? =
        { projectRebuilds(it) },
) : AdbTool {

    /** What [flutterRebuilds] is asked to record. */
    class FlutterRebuildRequest(
        val context: ToolContext,
        val device: ConnectedDevice,
        val packageName: String,
        val windowMs: Long,
        val cancelled: CancellationSignal,
        val limit: Int,
    )

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
            "is a lead, not proof of a performance problem. For a Flutter app in a debug build run with " +
            "flutter run (a DDS session), it records Flutter widget rebuilds instead, in the same shape: " +
            "Spock switches the inspector's rebuild tracking on for the window only if it was off, and " +
            "off again afterwards, and lists widgets built in every frame for a second or more."
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
        val cancelled = context.cancellationSignal()

        val request = FlutterRebuildRequest(context, device, packageName, seconds * MILLIS_PER_SECOND, cancelled, limit)
        flutter(request, includeLibraries)?.let { return it }

        val counts = try {
            RecompositionOperations(device.device, device.serialNumber, cancelled)
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

    /** The answer for a Flutter app, or null for any other, which records Compose. */
    private fun flutter(request: FlutterRebuildRequest, includeLibraries: Boolean): ToolResult? =
        when (val flutter = flutterRebuilds(request)) {
            null -> null
            is FlutterRebuildRecorder.Result.Refused -> ToolResult.error(flutter.reason)
            is FlutterRebuildRecorder.Result.Recorded ->
                ToolResult.text(renderRebuilds(flutter, request.packageName, includeLibraries))
        }

    /**
     * A Flutter rebuild window in the shape of [render]: a headline, then one line per widget
     * location, most built first, then the widgets built in every frame and what became of the
     * tracking flag.
     */
    internal fun renderRebuilds(
        recorded: FlutterRebuildRecorder.Result.Recorded,
        packageName: String,
        includeLibraries: Boolean,
    ): String = buildString {
        val report = recorded.report
        val seconds = String.format(Locale.ROOT, "%.1f", recorded.windowMs / MILLIS_PER_SECOND.toDouble())
        val rebuilds = report.top.sumOf { it.rebuilds }
        append("Recorded ").append(packageName).append(" for ").append(seconds).append("s (Flutter widget rebuilds): ")
        append(report.frames).append(" frame(s)")
        if (report.top.isNotEmpty()) {
            append(", ").append(rebuilds).append(" build(s) across the ").append(report.top.size)
            append(" most built widget location(s)")
        }
        append(".\n")
        if (report.frames == 0) {
            append(
                "Nothing was rendered while recording, which is what an idle screen should do; interact " +
                    "with it while recording to measure a change.\n",
            )
        } else {
            append("Count is times built, from the Flutter inspector's rebuild tracking; a widget that ")
            append("appeared during the recording counts its first build too. Only the app's own widgets ")
            append("are tracked")
            append(if (includeLibraries) ", so includeLibraries changes nothing for Flutter.\n\n" else ".\n\n")
            report.top.forEach { widget ->
                append(widget.rebuilds.toString().padStart(COUNT_WIDTH)).append("  ")
                append(widget.location?.name ?: "Widget").append("  (")
                val at = widget.location?.let { "${shortSourcePath(it.file)}:${it.line}:${it.column}" }
                append(at ?: "#${widget.id}")
                append(")\n")
            }
            if (report.problems.isNotEmpty()) {
                append("\nBuilt in every frame for a second or more:\n")
                report.problems.forEach { append("- ").append(it.summary).append('\n') }
            }
        }
        if (!recorded.seeded) append("\n").append(UNSEEDED)
        append("\n").append(trackingWords(recorded.tracking))
    }.trimEnd()

    private fun trackingWords(tracking: FlutterRebuildRecorder.Tracking): String = when (tracking) {
        FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF ->
            "Rebuild tracking: Spock switched it on for the recording and off again."
        FlutterRebuildRecorder.Tracking.ALREADY_ON ->
            "Rebuild tracking: it was on already (the IDE's rebuild counts or DevTools), so Spock left it on."
        FlutterRebuildRecorder.Tracking.LEFT_ON ->
            "Rebuild tracking: Spock switched it on and could not switch it off again (the session ended or " +
                "the app did not answer); it stays on until the app restarts."
        FlutterRebuildRecorder.Tracking.ISOLATE_GONE ->
            "Rebuild tracking: the app restarted during the recording, and the tracking Spock switched on " +
                "went with the old isolate; counts are from the new one."
    }

    private companion object {
        /** The project's Flutter session for a Flutter app; null, to record Compose, for any other or no project. */
        fun projectRebuilds(request: FlutterRebuildRequest): FlutterRebuildRecorder.Result? {
            val project = request.context.project ?: return null
            val device = request.device
            val build = FlutterBuildCache.shared.detectOn(device.device, device.serialNumber, request.packageName)
                ?: return null
            return FlutterFollowerService.getInstance(project).recordRebuilds(device, request.packageName, build) {
                it.record(request.windowMs, request.cancelled, request.limit)
            }
        }

        const val UNSEEDED = "Spock could not read the inspector's widget locations, so widgets the app " +
            "described before the recording appear by number."

        const val DEFAULT_SECONDS = 5
        const val MAX_SECONDS = 30
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 500
        const val COUNT_WIDTH = 6
        const val MILLIS_PER_SECOND = 1_000L
    }
}
