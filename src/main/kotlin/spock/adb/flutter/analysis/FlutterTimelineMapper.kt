package spock.adb.flutter.analysis

import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.vmservice.Redaction
import spock.adb.logcat.LogcatRedactor
import spock.adb.timeline.TimelineCategory
import spock.adb.timeline.TimelineEvent
import spock.adb.timeline.TimelineSeverity
import java.time.ZoneId

/**
 * Turns what the Flutter analyzers read into Debug Timeline events.
 *
 * Every event starts from the **device's** time, as the app stamped it: epoch milliseconds on the
 * device's clock. [Placement.toHostMs] moves it onto the timeline's host clock (P5b: the device
 * clock offset on Android, none on the simulator); left as identity, rows stay on the device's
 * clock. [TimelineEvent.deviceTime] always keeps the device stamp in logcat's format, so a row
 * can be matched against Logcat.
 *
 * Frames are the exception to "as the app stamped it": a `Flutter.Frame` event is stamped when
 * its batch was posted, not when the frame ran (see [FrameStats]). [Placement.frameStartOffsetMs]
 * — device epoch ms minus engine-monotonic ms, measured by the caller — places frames by their
 * own `startTime` instead.
 *
 * Only what is worth a row is mapped: every `Flutter.Error`, every `Flutter.Navigation`, bursts
 * of frames over budget (never a row per frame: a debug build misses the budget on every frame,
 * which would be sixty rows a second), and failed HTTP requests.
 *
 * Navigation rows name one route and nothing else, because that is all `Flutter.Navigation`
 * carries (Flutter 3.22, `NavigatorState._afterNavigation`): no push, pop or replace flag.
 * After a push, `pushReplacement`, `pushAndRemoveUntil` or `replace` it is the route now showing;
 * after a `pop` it is the route that was **popped**; after `removeRoute` of the current route it
 * is the route now showing, or none when the stack is empty. So a row says "Navigator: <route>",
 * never "opened". Declarative navigation (`Navigator.pages`, go_router) posts no event at all.
 */
object FlutterTimelineMapper {

    /**
     * Where the events belong.
     *
     * @param zone the **device's** time zone, for [TimelineEvent.deviceTime]. Required: logcat stamps
     *   are the device's local time, and the host's zone would print a row's stamp hours off.
     * @param frameStartOffsetMs device epoch ms minus engine-monotonic ms; null places frames by batch.
     * @param toHostMs device epoch ms to the timeline's host ms.
     */
    data class Placement(
        val zone: ZoneId,
        val deviceSerial: String? = null,
        val frameStartOffsetMs: Long? = null,
        val toHostMs: (Long) -> Long = { it },
    )

    /**
     * [events] in time order: errors, navigations and bursts of slow frames. Frames DDS replayed
     * on connect are app start-up, slow by nature, and are left out.
     *
     * Slow-frame rows are capped per call ([MAX_FRAME_ROWS]): pass a window of events, not the
     * whole session at once, and a burst that spans two windows is two rows.
     */
    fun map(
        events: List<FlutterExtensionEvent>,
        fps: Double?,
        build: FlutterBuild?,
        placement: Placement,
    ): List<TimelineEvent> {
        val rows = events.mapNotNull { event ->
            when (event.kind) {
                FlutterExtensionEvent.ERROR -> error(FlutterErrorReader.read(event), placement)
                FlutterExtensionEvent.NAVIGATION -> navigation(event, placement)
                else -> null
            }
        }
        val frames = FrameStats.read(events).filterNot { it.history }
        return (rows + slowFrames(frames, FrameStats.budgetMs(fps), build, placement)).sortedBy { it.timeMs }
    }

    fun error(error: FlutterErrorReader.FlutterError, placement: Placement): TimelineEvent =
        TimelineEvent(
            timeMs = placement.toHostMs(error.timestampMs),
            category = TimelineCategory.FLUTTER_ERROR,
            severity = TimelineSeverity.ERROR,
            title = redact(error.summary).take(TITLE_LIMIT),
            detail = ((if (error.history) REPLAYED + "\n" else "") + redact(error.renderedText)).take(DETAIL_LIMIT),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(error.timestampMs, placement.zone),
        )

    /**
     * One route, by its name (else its description). Which route that is depends on the call the
     * app made — see the class comment. Route arguments are left out: they can carry user data.
     */
    fun navigation(event: FlutterExtensionEvent, placement: Placement): TimelineEvent? {
        if (event.kind != FlutterExtensionEvent.NAVIGATION) return null
        val route = FlutterJson.obj(event.data, "route")
        val name = FlutterJson.string(FlutterJson.obj(route, "settings"), "name")
        val description = FlutterJson.string(route, "description")
        val label = when {
            route == null -> "no route showing"
            else -> name ?: description ?: "a route with no name"
        }
        return TimelineEvent(
            timeMs = placement.toHostMs(event.timestampMs),
            category = TimelineCategory.NAVIGATION,
            severity = TimelineSeverity.INFO,
            title = redact("Navigator: $label").take(TITLE_LIMIT),
            detail = listOfNotNull(
                description?.let { "Route: $it" },
                if (event.history) REPLAYED else null,
            ).joinToString("\n").let(::redact),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(event.timestampMs, placement.zone),
        )
    }

    /**
     * One row per burst of frames over budget: slow frames less than [BURST_GAP_MS] apart (from
     * one's end to the next one's start) are one burst, whatever ran between them, so jank that
     * alternates with good frames is one row and not thirty a second. A row gives the count, the
     * frames in its span and the worst build and raster.
     *
     * In profile and release builds a WARNING when the burst is jank by [FrameStats.isJanky] — the
     * rule the Diagnose report uses, so the two never disagree — and INFO for a hiccup; INFO in
     * debug or an unknown build, where every frame is slow and the times are not representative
     * [FR4]. At most [MAX_FRAME_ROWS] rows; the
     * bursts past it are one more row, so a window of constant jank cannot flood the timeline.
     *
     * @param frames in the order the app sent them; replayed ones should already be left out.
     */
    fun slowFrames(
        frames: List<FrameStats.Frame>,
        budgetMs: Double,
        build: FlutterBuild?,
        placement: Placement,
    ): List<TimelineEvent> {
        val bursts = bursts(frames, budgetMs)
        val shown = if (bursts.size <= MAX_FRAME_ROWS) bursts else bursts.take(MAX_FRAME_ROWS - 1)
        val rest = bursts.drop(shown.size).takeIf { it.isNotEmpty() }?.let { more ->
            Burst(more.flatMap { it.slow }, more.sumOf { it.frames }, merged = more.size)
        }
        return (shown + listOfNotNull(rest)).map { burstRow(it, budgetMs, build, placement) }
    }

    /** A failed request's row, with the request's id to keep one row per request by. */
    data class HttpFailure(val requestId: String, val row: TimelineEvent)

    /**
     * Null for a request that did not fail. Placed at its start.
     *
     * A 4xx/5xx is failed as soon as its status is in, so a request read on one `updatedSince`
     * page in flight and on the next finished maps twice. Callers keep one row per [HttpFailure.requestId],
     * the later replacing the earlier.
     */
    fun httpFailure(request: HttpProfileReader.Request, placement: Placement): HttpFailure? {
        if (!request.failed) return null
        val deviceMs = (request.startTimeUs ?: request.endTimeUs ?: return null) / MICROS_PER_MILLI
        val severity = when (HttpProfileReader.severity(request)) {
            Severity.WARNING -> TimelineSeverity.WARNING
            else -> TimelineSeverity.ERROR
        }
        val row = TimelineEvent(
            timeMs = placement.toHostMs(deviceMs),
            category = TimelineCategory.HTTP,
            severity = severity,
            title = HttpProfileReader.summary(request).take(TITLE_LIMIT),
            detail = when {
                request.durationMs != null -> "Took ${request.durationMs} ms."
                request.error == null -> "The response body had not been read to its end."
                else -> ""
            },
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(deviceMs, placement.zone),
        )
        return HttpFailure(request.id, row)
    }

    /** Slow frames, and how many frames ran from the first to the last of them. */
    private class Burst(val slow: List<FrameStats.Frame>, val frames: Int, val merged: Int = 1)

    private fun bursts(frames: List<FrameStats.Frame>, budgetMs: Double): List<Burst> {
        val bursts = mutableListOf<Burst>()
        var slow = mutableListOf<FrameStats.Frame>()
        var span = 0
        var spanSinceSlow = 0
        fun close() {
            if (slow.isNotEmpty()) bursts += Burst(slow, span - spanSinceSlow)
            slow = mutableListOf()
            span = 0
            spanSinceSlow = 0
        }
        for (frame in frames) {
            val over = FrameStats.isOverBudget(frame, budgetMs)
            val previous = slow.lastOrNull()
            if (over && previous != null &&
                frame.startTimeUs - (previous.startTimeUs + previous.elapsedUs) > BURST_GAP_MS * MICROS_PER_MILLI
            ) {
                close()
            }
            if (over) {
                slow += frame
                spanSinceSlow = 0
            } else {
                spanSinceSlow++
            }
            if (slow.isNotEmpty()) span++
        }
        close()
        return bursts
    }

    private fun burstRow(burst: Burst, budgetMs: Double, build: FlutterBuild?, placement: Placement): TimelineEvent {
        val representative = build == FlutterBuild.PROFILE || build == FlutterBuild.RELEASE
        val first = burst.slow.first()
        val offset = placement.frameStartOffsetMs
        val deviceMs = offset?.let { first.startTimeUs / MICROS_PER_MILLI + it } ?: first.postedAtMs
        val count = burst.slow.size
        val numbers = if (count == 1) "#${first.number}" else "#${first.number}–#${burst.slow.last().number}"
        return TimelineEvent(
            timeMs = placement.toHostMs(deviceMs),
            category = TimelineCategory.FLUTTER_FRAME,
            severity = if (representative && FrameStats.isJanky(burst.frames, burst.slow)) {
                TimelineSeverity.WARNING
            } else {
                TimelineSeverity.INFO
            },
            title = if (count == 1) {
                "Slow frame: build ${micros(first.buildUs)}, raster ${micros(first.rasterUs)} " +
                    "(budget ${FrameStats.ms(budgetMs)})"
            } else {
                "$count of ${burst.frames} frames over the ${FrameStats.ms(budgetMs)} budget; " +
                    "worst build ${micros(burst.slow.maxOf { it.buildUs })}, " +
                    "raster ${micros(burst.slow.maxOf { it.rasterUs })}"
            },
            detail = listOfNotNull(
                "Frames $numbers.",
                "${burst.merged} bursts in one row: this window had more than $MAX_FRAME_ROWS."
                    .takeIf { burst.merged > 1 },
                notRepresentative(build).takeIf { !representative },
                "Placed when the first frame's batch of timings arrived.".takeIf { offset == null },
            ).joinToString("\n"),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(deviceMs, placement.zone),
        )
    }

    private fun notRepresentative(build: FlutterBuild?): String =
        "${if (build == null) "Unknown build mode" else "Debug build"}: frame times are not representative; " +
            "measure jank in a profile build."

    private fun micros(value: Long): String = FrameStats.ms(value / MICROS_PER_MILLI_D)

    private fun redact(text: String): String = LogcatRedactor.redact(stripUrlQueries(Redaction.scrub(text))).text

    private const val REPLAYED = "Replayed on connect: this happened before Spock connected."
    private const val TITLE_LIMIT = 200

    /** The first error after a reload renders its whole report; enough of it to read the cause. */
    private const val DETAIL_LIMIT = 8_000
    private const val MICROS_PER_MILLI = 1000L
    private const val MICROS_PER_MILLI_D = 1000.0

    /**
     * Slow frames closer than this are one burst. A janky animation drops frames every few
     * hundred ms at worst; a second of smooth frames or idle starts a new one.
     */
    const val BURST_GAP_MS = 1_000L

    /** Slow-frame rows per [map] or [slowFrames] call, the last of them standing for the rest. */
    const val MAX_FRAME_ROWS = 20
}
