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
 * Every event is placed at the **device's** time, as the app stamped it: epoch milliseconds on
 * the device's clock, not the host's. Moving them onto the timeline's host clock is the
 * caller's job (P5b: the device clock offset on Android, none on the simulator), by
 * `copy(timeMs = timeMs + offset)`. [TimelineEvent.deviceTime] keeps the device stamp in
 * logcat's format, so a row can be matched against Logcat.
 *
 * Frames are the exception to "as the app stamped it": a `Flutter.Frame` event is stamped when
 * its batch was posted, not when the frame ran (see [FrameStats]). [Placement.frameStartOffsetMs]
 * — epoch ms minus engine-monotonic ms, measured by the caller — places each frame by its own
 * `startTime` instead.
 *
 * Only what is worth a row is mapped: every `Flutter.Error`, every `Flutter.Navigation`, frames
 * over budget, and failed HTTP requests.
 */
object FlutterTimelineMapper {

    /**
     * Where the events belong.
     *
     * @param zone the device's time zone, for [TimelineEvent.deviceTime].
     * @param frameStartOffsetMs epoch ms minus engine-monotonic ms; null places frames by batch.
     */
    data class Placement(
        val deviceSerial: String? = null,
        val zone: ZoneId = ZoneId.systemDefault(),
        val frameStartOffsetMs: Long? = null,
    )

    /** [events] in time order: errors, navigations and over-budget frames. */
    fun map(
        events: List<FlutterExtensionEvent>,
        fps: Double?,
        build: FlutterBuild?,
        placement: Placement = Placement(),
    ): List<TimelineEvent> {
        val budget = FrameStats.budgetMs(fps)
        return events.mapNotNull { event ->
            when (event.kind) {
                FlutterExtensionEvent.ERROR -> error(FlutterErrorReader.read(event), placement)
                FlutterExtensionEvent.NAVIGATION -> navigation(event, placement)
                FlutterExtensionEvent.FRAME -> FrameStats.read(event)?.let { jankFrame(it, budget, build, placement) }
                else -> null
            }
        }.sortedBy { it.timeMs }
    }

    fun error(error: FlutterErrorReader.FlutterError, placement: Placement = Placement()): TimelineEvent =
        TimelineEvent(
            timeMs = error.timestampMs,
            category = TimelineCategory.FLUTTER_ERROR,
            severity = TimelineSeverity.ERROR,
            title = redact(error.summary).take(TITLE_LIMIT),
            detail = ((if (error.history) REPLAYED + "\n" else "") + redact(error.renderedText)).take(DETAIL_LIMIT),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(error.timestampMs, placement.zone),
        )

    /**
     * `Flutter.Navigation` names a route and nothing else: not whether it was pushed, popped or
     * replaced. On a pop it names the route that was **popped**, not the one now showing, so the
     * title says "Navigator" and never "opened". Declarative navigation (`Navigator.pages`,
     * go_router) posts no event at all. Route arguments are left out: they can carry user data.
     */
    fun navigation(event: FlutterExtensionEvent, placement: Placement = Placement()): TimelineEvent? {
        if (event.kind != FlutterExtensionEvent.NAVIGATION) return null
        val route = FlutterJson.obj(event.data, "route")
        val name = FlutterJson.string(FlutterJson.obj(route, "settings"), "name")
        val description = FlutterJson.string(route, "description")
        val label = name ?: description ?: "a route with no name"
        return TimelineEvent(
            timeMs = event.timestampMs,
            category = TimelineCategory.NAVIGATION,
            severity = TimelineSeverity.INFO,
            title = redact("Navigator: $label").take(TITLE_LIMIT),
            detail = listOfNotNull(
                description?.let { "Route: $it" },
                "On a pop this is the route that was removed, not the one now showing.",
                if (event.history) REPLAYED else null,
            ).joinToString("\n").let(::redact),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(event.timestampMs, placement.zone),
        )
    }

    /**
     * Null for a frame within budget. A slow frame is a WARNING in profile and release builds and
     * INFO in debug (or an unknown build), where frame times are not representative [FR4].
     */
    fun jankFrame(
        frame: FrameStats.Frame,
        budgetMs: Double,
        build: FlutterBuild?,
        placement: Placement = Placement(),
    ): TimelineEvent? {
        if (!FrameStats.isOverBudget(frame, budgetMs)) return null
        val representative = build == FlutterBuild.PROFILE || build == FlutterBuild.RELEASE
        val offset = placement.frameStartOffsetMs
        val timeMs = offset?.let { frame.startTimeUs / MICROS_PER_MILLI + it } ?: frame.postedAtMs
        return TimelineEvent(
            timeMs = timeMs,
            category = TimelineCategory.FLUTTER_FRAME,
            severity = if (representative) TimelineSeverity.WARNING else TimelineSeverity.INFO,
            title = "Slow frame #${frame.number}: build ${micros(frame.buildUs)}, raster ${micros(frame.rasterUs)} " +
                "(budget ${FrameStats.ms(budgetMs)})",
            detail = listOfNotNull(
                "Total ${micros(frame.elapsedUs)}, vsync overhead ${micros(frame.vsyncOverheadUs)}.",
                if (representative) null else "Debug build: frame times are not representative.",
                if (offset == null) "Placed when its batch of frame timings arrived." else null,
            ).joinToString("\n"),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(timeMs, placement.zone),
        )
    }

    /** Null for a request that did not fail, or has not finished. Placed at its start. */
    fun httpFailure(request: HttpProfileReader.Request, placement: Placement = Placement()): TimelineEvent? {
        if (!request.failed) return null
        val timeMs = (request.startTimeUs ?: request.endTimeUs ?: return null) / MICROS_PER_MILLI
        val severity = when (HttpProfileReader.severity(request)) {
            Severity.WARNING -> TimelineSeverity.WARNING
            else -> TimelineSeverity.ERROR
        }
        return TimelineEvent(
            timeMs = timeMs,
            category = TimelineCategory.HTTP,
            severity = severity,
            title = HttpProfileReader.summary(request).take(TITLE_LIMIT),
            detail = request.durationMs?.let { "Took $it ms." }.orEmpty(),
            deviceSerial = placement.deviceSerial,
            deviceTime = logcatTime(timeMs, placement.zone),
        )
    }

    private fun micros(value: Long): String = FrameStats.ms(value / MICROS_PER_MILLI_D)

    private fun redact(text: String): String = LogcatRedactor.redact(Redaction.scrub(text)).text

    private const val REPLAYED = "Replayed on connect: this happened before Spock connected."
    private const val TITLE_LIMIT = 200

    /** The first error after a reload renders its whole report; enough of it to read the cause. */
    private const val DETAIL_LIMIT = 8_000
    private const val MICROS_PER_MILLI = 1000L
    private const val MICROS_PER_MILLI_D = 1000.0
}
