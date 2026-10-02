package spock.adb.flutter.analysis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import spock.adb.diagnostics.LikelyProblem
import spock.adb.diagnostics.LikelyProblem.Severity
import java.util.Locale

/**
 * Adds up `Flutter.RebuiltWidgets` over a recording window and names the widgets built most.
 *
 * While `ext.flutter.inspector.trackRebuildDirtyWidgets` is on (debug only), the inspector posts
 * one event at the end of every rendered frame (Flutter 3.22, `widget_inspector.dart`):
 * - `events`: `[id, count, id, count, …]` — how many elements created at each source location
 *   were built in that frame, for the project's own locations only. A frame with no builds posts
 *   an empty list, so events also count frames; an idle app renders, and posts, nothing;
 * - `locations` (`{file: {ids, lines, columns, names}}`) and `newLocations` (the pre-2.4
 *   encoding, `{file: [id, line, column, …]}`, no names): both carry only the locations first
 *   seen since the previous event. 3.22 sends both; older versions only `newLocations`. Each
 *   location is sent **once per isolate**, not once per recording, so a second window — or one
 *   opened after the IDE's rebuild counts were on — sees ids it was never told about.
 *   [seedLocations] takes the full map from `ext.flutter.inspector.widgetLocationIdMap` to fill
 *   that gap; an id still unknown is reported by number.
 *
 * Location ids are per isolate: a hot restart starts a new isolate that numbers them again, so
 * an event from another isolate starts the window over. Events DDS replayed on connect are left
 * out: they are from before the window.
 *
 * Thread-safe: [accept], [seedLocations] and [report] may be called from different threads.
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
        /** The most frames in a row in which this location was built at least once. */
        val longestRun: Int,
    ) {
        val label: String get() = location?.toString() ?: "Widget location #$id"
    }

    data class Report(
        val frames: Int,
        val windowMs: Long?,
        val top: List<WidgetRebuilds>,
        val problems: List<LikelyProblem>,
    )

    private val lock = Any()
    private var isolateId: String? = null
    private val locations = mutableMapOf<Int, Location>()
    private val counts = mutableMapOf<Int, Long>()

    /** Per location: frames in a row built at least once, at least [HEAVY_REBUILDS_PER_FRAME] times. */
    private val runs = mutableMapOf<Int, Int>()
    private val heavyRuns = mutableMapOf<Int, Int>()
    private val longestRuns = mutableMapOf<Int, Int>()
    private val longestHeavyRuns = mutableMapOf<Int, Int>()
    private var frames = 0
    private var firstFrameUs: Long? = null
    private var lastFrameUs: Long? = null

    /**
     * The result of `ext.flutter.inspector.widgetLocationIdMap`: every location the isolate knows.
     *
     * @param isolateId the isolate it was read from; another isolate than the events' starts the
     *   window over, as an event from it would.
     */
    fun seedLocations(locationMap: JsonObject, isolateId: String? = null) = synchronized(lock) {
        follow(isolateId)
        readLocations(locationMap)
    }

    /** Takes one live `Flutter.RebuiltWidgets` event; anything else, replayed or malformed, is ignored. */
    fun accept(event: FlutterExtensionEvent) {
        if (event.kind != FlutterExtensionEvent.REBUILT_WIDGETS || event.history) return
        val data = event.data
        val built = linkedMapOf<Int, Long>()
        FlutterJson.longs(FlutterJson.array(data, "events")).chunked(2).forEach { pair ->
            val id = pair.first()?.toInt()
            val count = pair.getOrNull(1)?.takeIf { it > 0 }
            if (id != null && count != null) built.merge(id, count, Long::plus)
        }
        synchronized(lock) {
            follow(event.isolateId)
            FlutterJson.obj(data, "locations")?.let(::readLocations)
            FlutterJson.obj(data, "newLocations")?.let(::readLegacyLocations)
            built.forEach { (id, count) -> counts.merge(id, count, Long::plus) }
            extendRuns(runs, longestRuns, built.keys)
            extendRuns(heavyRuns, longestHeavyRuns, built.filterValues { it >= HEAVY_REBUILDS_PER_FRAME }.keys)
            frames++
            FlutterJson.long(data, "startTime")?.let { start ->
                if (firstFrameUs == null) firstFrameUs = start
                lastFrameUs = start
            }
        }
    }

    fun acceptAll(events: List<FlutterExtensionEvent>) = events.forEach(::accept)

    /**
     * @param windowMs how long the recording ran, as the caller measured it. Null derives it from
     *   the frames' own start times, which miss the idle time after the last frame.
     * @param limit how many widgets to list.
     */
    fun report(windowMs: Long? = null, limit: Int = DEFAULT_LIMIT): Report = synchronized(lock) {
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
                    longestRun = longestRuns[id] ?: 0,
                )
            }
        val frequent = ranked.filter { it.longestRun >= MIN_RUN_FRAMES }
        Report(
            frames = frames,
            windowMs = window,
            top = ranked.take(limit),
            problems = frequent.take(limit).map(::problem),
        )
    }

    /**
     * A hint, worded as what was seen: "rebuilt N times in M frames". A WARNING only when the
     * location was built [HEAVY_REBUILDS_PER_FRAME] times in each frame of a run as long.
     */
    private fun problem(widget: WidgetRebuilds): LikelyProblem {
        val rate = widget.perSecond?.let { String.format(Locale.ROOT, "%.0f/s, ", it) }.orEmpty()
        val perFrame = String.format(Locale.ROOT, "%.1f", widget.perFrame)
        val heavy = (longestHeavyRuns[widget.id] ?: 0) >= MIN_RUN_FRAMES
        return LikelyProblem(
            type = TYPE,
            severity = if (heavy) Severity.WARNING else Severity.INFO,
            summary = "${widget.label} rebuilt ${widget.rebuilds} times in $frames frames, in every one of " +
                "${widget.longestRun} frames in a row (${rate}$perFrame per frame)",
            count = widget.rebuilds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            section = FLUTTER_SECTION,
        )
    }

    /** Locations built in this frame extend their run; every other run ends. */
    private fun extendRuns(current: MutableMap<Int, Int>, longest: MutableMap<Int, Int>, built: Set<Int>) {
        current.keys.retainAll(built)
        built.forEach { id ->
            val run = (current[id] ?: 0) + 1
            current[id] = run
            longest[id] = maxOf(longest[id] ?: 0, run)
        }
    }

    /** A new isolate numbers locations from scratch: what was counted under the old ids is dropped. */
    private fun follow(eventIsolate: String?) {
        if (eventIsolate == null || eventIsolate == isolateId) return
        if (isolateId != null) {
            locations.clear()
            counts.clear()
            runs.clear()
            heavyRuns.clear()
            longestRuns.clear()
            longestHeavyRuns.clear()
            frames = 0
            firstFrameUs = null
            lastFrameUs = null
        }
        isolateId = eventIsolate
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
        /** The problem type for a widget built in every frame of a sustained run. */
        const val TYPE = FlutterProblemTypes.FREQUENT_REBUILDS

        /**
         * How many frames in a row a location must be built in to be reported: at 60 Hz, a second.
         *
         * A frame's count cannot tell a rebuild from a first build: the inspector's hook ignores
         * `builtOnce`, and counts per creation location, not per element (Flutter 3.22,
         * `_onRebuildWidget`). So scrolling a `ListView.builder` counts each item that scrolls in
         * at its `itemBuilder`'s location, and a burst of new items can average one or more per
         * frame. What a first build cannot do is repeat in **every** frame for a second: that
         * takes something that ticks — an animation, a stream, a `setState` in a listener, a
         * parent recreating the widget — or a scroll fast enough to bring a new item in on each
         * frame for a whole second. An indeterminate progress indicator animates inside the
         * framework's own widgets, which are not counted, so it adds frames and no builds.
         *
         * Not validated on a device yet (plan H3): the sample's Frames screen has the cases.
         */
        const val MIN_RUN_FRAMES = 60

        /**
         * Builds per frame, in every frame of a [MIN_RUN_FRAMES] run, that make the hint a
         * warning: a list's worth of widgets rebuilt on every frame, which no scroll produces.
         */
        const val HEAVY_REBUILDS_PER_FRAME = 10L

        const val DEFAULT_LIMIT = 10

        private const val LEGACY_STRIDE = 3
        private const val MILLIS_PER_SECOND = 1000.0
        private const val MICROS_PER_MILLI = 1000
    }
}
