package spock.adb.flutter.analysis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import spock.adb.diagnostics.LikelyProblem
import spock.adb.diagnostics.LikelyProblem.Severity
import java.util.Locale

/**
 * Adds up `Flutter.RebuiltWidgets` over a recording window and names the widgets rebuilt most.
 *
 * While `ext.flutter.inspector.trackRebuildDirtyWidgets` is on (debug only), the inspector posts
 * one event at the end of every frame (Flutter 3.22, `widget_inspector.dart`):
 * - `events`: `[id, count, id, count, …]` — how often each widget creation location was rebuilt
 *   in that frame. A frame with no rebuilds posts an empty list, so events also count frames;
 * - `locations` (`{file: {ids, lines, columns, names}}`) and `newLocations` (the pre-2.4
 *   encoding, `{file: [id, line, column, …]}`, no names): both carry only the locations first
 *   seen since the previous event, and only the project's own. 3.22 sends both; older versions
 *   only `newLocations`. Each location is sent **once per isolate**, not once per recording, so a
 *   second window — or one opened after the IDE's rebuild counts were on — sees ids it was never
 *   told about. [seedLocations] takes the full map from `ext.flutter.inspector.widgetLocationIdMap`
 *   to fill that gap; an id still unknown is reported by number.
 *
 * Not thread-safe: the caller feeds it from one thread.
 */
class RebuildTracker {

    data class Location(val file: String, val line: Int, val column: Int, val name: String?) {
        /** `ListTile at lib/main.dart:89:22`. */
        override fun toString(): String =
            "${name ?: "Widget"} at ${shortSourcePath(file)}:$line:$column"
    }

    data class WidgetRebuilds(
        val id: Int,
        /** Null when the app never described this id to Spock. */
        val location: Location?,
        val rebuilds: Long,
        /** Over the window, or null when it is too short to tell. */
        val perSecond: Double?,
        val perFrame: Double,
    ) {
        val label: String get() = location?.toString() ?: "Widget location #$id"
    }

    data class Report(
        val frames: Int,
        val windowMs: Long?,
        val top: List<WidgetRebuilds>,
        val problems: List<LikelyProblem>,
    )

    private val locations = mutableMapOf<Int, Location>()
    private val counts = mutableMapOf<Int, Long>()
    private var frames = 0
    private var firstFrameUs: Long? = null
    private var lastFrameUs: Long? = null

    /** The result of `ext.flutter.inspector.widgetLocationIdMap`: every location the isolate knows. */
    fun seedLocations(locationMap: JsonObject) = readLocations(locationMap)

    /** Takes one `Flutter.RebuiltWidgets` event; anything else, or a malformed one, is ignored. */
    fun accept(event: FlutterExtensionEvent) {
        if (event.kind != FlutterExtensionEvent.REBUILT_WIDGETS) return
        val data = event.data
        FlutterJson.obj(data, "locations")?.let(::readLocations)
        FlutterJson.obj(data, "newLocations")?.let(::readLegacyLocations)
        FlutterJson.longs(FlutterJson.array(data, "events")).chunked(2).forEach { pair ->
            val id = pair.first()?.toInt()
            val count = pair.getOrNull(1)?.takeIf { it > 0 }
            if (id != null && count != null) counts.merge(id, count, Long::plus)
        }
        frames++
        FlutterJson.long(data, "startTime")?.let { start ->
            if (firstFrameUs == null) firstFrameUs = start
            lastFrameUs = start
        }
    }

    fun acceptAll(events: List<FlutterExtensionEvent>) = events.forEach(::accept)

    /**
     * @param windowMs how long the recording ran, as the caller measured it. Null derives it from
     *   the frames' own start times, which miss the idle time after the last frame.
     * @param limit how many widgets to list.
     */
    fun report(windowMs: Long? = null, limit: Int = DEFAULT_LIMIT): Report {
        val window = windowMs ?: derivedWindowMs()
        val seconds = window?.takeIf { it > 0 }?.let { it / MILLIS_PER_SECOND }
        val ranked = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .map { (id, rebuilds) ->
                WidgetRebuilds(
                    id = id,
                    location = locations[id],
                    rebuilds = rebuilds,
                    perSecond = seconds?.let { rebuilds / it },
                    perFrame = if (frames == 0) 0.0 else rebuilds.toDouble() / frames,
                )
            }
        val storms = if (frames < STORM_MIN_FRAMES) {
            emptyList()
        } else {
            ranked.filter { it.perFrame >= STORM_REBUILDS_PER_FRAME }
        }
        return Report(
            frames = frames,
            windowMs = window,
            top = ranked.take(limit),
            problems = storms.take(limit).map(::storm),
        )
    }

    private fun storm(widget: WidgetRebuilds): LikelyProblem {
        val rate = widget.perSecond?.let { String.format(Locale.ROOT, "%.0f/s, ", it) }.orEmpty()
        val perFrame = String.format(Locale.ROOT, "%.1f", widget.perFrame)
        return LikelyProblem(
            type = TYPE,
            severity = Severity.WARNING,
            summary = "Rebuild storm: ${widget.label} rebuilt ${widget.rebuilds} times in $frames frames " +
                "(${rate}$perFrame per frame)",
            count = widget.rebuilds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            section = FLUTTER_SECTION,
        )
    }

    private fun derivedWindowMs(): Long? {
        val first = firstFrameUs ?: return null
        val last = lastFrameUs ?: return null
        return ((last - first) / MICROS_PER_MILLI).takeIf { it > 0 }
    }

    /** `{file: {ids: [...], lines: [...], columns: [...], names: [...]}}`. */
    private fun readLocations(map: JsonObject) {
        for ((file, value) in map.entrySet()) {
            val entry = value as? JsonObject ?: continue
            val ids = FlutterJson.longs(FlutterJson.array(entry, "ids"))
            val lines = FlutterJson.longs(FlutterJson.array(entry, "lines"))
            val columns = FlutterJson.longs(FlutterJson.array(entry, "columns"))
            val names = FlutterJson.strings(FlutterJson.array(entry, "names"))
            ids.forEachIndexed { index, id ->
                val location = location(file, lines.getOrNull(index), columns.getOrNull(index), names.getOrNull(index))
                if (id != null && location != null) locations[id.toInt()] = location
            }
        }
    }

    /** `{file: [id, line, column, id, line, column, …]}`; never overwrites a named location. */
    private fun readLegacyLocations(map: JsonObject) {
        for ((file, value) in map.entrySet()) {
            FlutterJson.longs(value as? JsonArray).chunked(LEGACY_STRIDE).forEach { triple ->
                val id = triple.first()
                val location = location(file, triple.getOrNull(1), triple.getOrNull(2), name = null)
                if (id != null && location != null) locations.putIfAbsent(id.toInt(), location)
            }
        }
    }

    private fun location(file: String, line: Long?, column: Long?, name: String?): Location? =
        if (line == null || column == null) null else Location(file, line.toInt(), column.toInt(), name)

    companion object {
        /** The problem type for a widget rebuilt in (almost) every frame. */
        const val TYPE = "rebuildStorm"

        /**
         * A widget rebuilt at least once per frame, on average, is being rebuilt by something
         * that ticks — an animation, a stream, a `setState` in a listener, a parent recreating
         * it — rather than by input, which rebuilds in bursts.
         */
        const val STORM_REBUILDS_PER_FRAME = 1.0

        /**
         * At 60 Hz, one second of frames. A tap that rebuilds a screen across a handful of frames
         * is one rebuild per frame too; this keeps it from reading as a storm.
         */
        const val STORM_MIN_FRAMES = 60

        const val DEFAULT_LIMIT = 10

        private const val LEGACY_STRIDE = 3
        private const val MILLIS_PER_SECOND = 1000.0
        private const val MICROS_PER_MILLI = 1000
    }
}
