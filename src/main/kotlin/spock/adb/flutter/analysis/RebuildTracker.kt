package spock.adb.flutter.analysis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import spock.adb.diagnostics.DiagnosticShell
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
 * A location is reported when it was built in every frame for [RebuildRuns.MIN_RUN_MS] (see there
 * why every frame, and not an average). That cannot tell a rebuild storm from an animation that
 * loops on purpose, so the hint names the animation case rather than hiding it.
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
        val longestRunFrames: Int,
        /** How long that run lasted, first frame start to last; null when frames had no start time. */
        val longestRunMs: Long?,
    ) {
        val label: String get() = location?.toString() ?: "Widget location #$id"

        /** An animation widget (`AnimatedBuilder`, `*Transition`, `Animated*`, …): built every frame by design. */
        val animates: Boolean get() = location?.name?.let(ANIMATION::matches) == true
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

    /** Runs of frames each location was built in; [heavyRuns] counts only [HEAVY_REBUILDS_PER_FRAME] or more. */
    private val runs = RebuildRuns()
    private val heavyRuns = RebuildRuns()
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
        val start = FlutterJson.long(data, "startTime")
        synchronized(lock) {
            follow(event.isolateId)
            FlutterJson.obj(data, "locations")?.let(::readLocations)
            FlutterJson.obj(data, "newLocations")?.let(::readLegacyLocations)
            built.forEach { (id, count) -> counts.merge(id, count, Long::plus) }
            runs.frame(start, built.keys)
            heavyRuns.frame(start, built.filterValues { it >= HEAVY_REBUILDS_PER_FRAME }.keys)
            frames++
            if (start != null) {
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
                    longestRunFrames = runs.longest(id)?.frames ?: 0,
                    longestRunMs = runs.longest(id)?.spanMs,
                )
            }
        val frequent = ranked.filter { runs.longest(it.id)?.sustained == true }
        Report(
            frames = frames,
            windowMs = window,
            top = ranked.take(limit),
            problems = frequent.take(limit).map { problem(it, frequent) },
        )
    }

    /**
     * A hint, worded as what was seen: "rebuilt N times in M frames, in every frame for T". An
     * animation widget is built every frame by design, so its hint says that is expected while it
     * animates; so does the hint of a widget whose run started on the same frame as an animation's,
     * which that animation's builder probably makes. Never suppressed: a looping animation nobody
     * sees is worth knowing about too. A WARNING only when a widget that is not an animation was
     * built [HEAVY_REBUILDS_PER_FRAME] times in every frame of a sustained run.
     */
    private fun problem(widget: WidgetRebuilds, frequent: List<WidgetRebuilds>): LikelyProblem {
        val rate = widget.perSecond?.let { String.format(Locale.ROOT, "%.0f/s, ", it) }.orEmpty()
        val perFrame = String.format(Locale.ROOT, "%.1f", widget.perFrame)
        val run = widget.longestRunMs
            ?.let { String.format(Locale.ROOT, "%.1f s (%d frames)", it / MILLIS_PER_SECOND, widget.longestRunFrames) }
            ?: "${widget.longestRunFrames} frames"
        val driver = driver(widget, frequent)
        val hint = when {
            widget.animates -> ANIMATES_HINT
            driver == null -> ""
            driver.location?.file == widget.location?.file ->
                " — in step with ${driver.location?.name} (line ${driver.location?.line}): expected if it builds this"
            else -> " — in step with ${driver.label}: expected if it builds this"
        }
        val heavy = !widget.animates && heavyRuns.longest(widget.id)?.sustained == true
        val seen = "${widget.label} rebuilt ${widget.rebuilds} times in $frames frames, in every frame for $run " +
            "(${rate}$perFrame per frame)"
        return LikelyProblem(
            type = TYPE,
            severity = if (heavy) Severity.WARNING else Severity.INFO,
            // Clipped before the hint, so a long label cannot cut off why it may be fine.
            summary = DiagnosticShell.clip(seen, DiagnosticShell.MAX_VALUE_CHARS - hint.length) + hint,
            count = widget.rebuilds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            section = FLUTTER_SECTION,
        )
    }

    /**
     * The animation whose run started on the same frame as [widget]'s, if any. Of several, the one
     * in the same file closest above it: a builder's widgets are written inside the animation that
     * builds them.
     */
    private fun driver(widget: WidgetRebuilds, frequent: List<WidgetRebuilds>): WidgetRebuilds? {
        val startUs = runs.longest(widget.id)?.startUs ?: return null
        val inStep = frequent.filter { it.animates && it.id != widget.id && runs.longest(it.id)?.startUs == startUs }
        val here = widget.location ?: return inStep.firstOrNull()
        return inStep
            .filter { it.location != null && it.location.file == here.file && it.location.line <= here.line }
            .maxByOrNull { it.location?.line ?: 0 }
            ?: inStep.firstOrNull()
    }

    /** A new isolate numbers locations from scratch: what was counted under the old ids is dropped. */
    private fun follow(eventIsolate: String?) {
        if (eventIsolate == null || eventIsolate == isolateId) return
        if (isolateId != null) {
            locations.clear()
            counts.clear()
            runs.clear()
            heavyRuns.clear()
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
         * Builds per frame, in every frame of a sustained run, that make the hint a warning: a
         * list's worth of widgets rebuilt on every frame, which no scroll produces.
         */
        const val HEAVY_REBUILDS_PER_FRAME = 10L

        const val DEFAULT_LIMIT = 10

        /** Flutter's animation widgets, which rebuild on every tick of their animation by design. */
        private val ANIMATION = Regex("""Animated[A-Z]\w*|TweenAnimationBuilder|\w+Transition""")

        private const val ANIMATES_HINT = " — expected if this widget animates continuously"

        private const val LEGACY_STRIDE = 3
        private const val MILLIS_PER_SECOND = 1000.0
        private const val MICROS_PER_MILLI = 1000
    }
}
