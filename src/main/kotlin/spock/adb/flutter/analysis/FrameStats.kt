package spock.adb.flutter.analysis

import com.google.gson.JsonObject
import spock.adb.diagnostics.LikelyProblem
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.flutter.FlutterBuild
import java.util.Locale

/**
 * Frame timings from `Flutter.Frame` events, against the display's frame budget.
 *
 * `Flutter.Frame` comes from the engine's `onReportTimings`, which has two consequences:
 * - it arrives in **batches** (within about 100 ms in debug and profile, a second in release),
 *   so an event's `timestamp` is when the batch was posted, not when the frame ran. A frame's
 *   own time is its `startTime`: engine-monotonic microseconds, not wall-clock;
 * - it reports only frames that were **rendered**. An idle app reports none, and an app stuck on
 *   its UI thread reports nothing until it unblocks — no frames is not the same as smooth frames.
 *
 * Durations (`build`, `raster`, `elapsed`) are microseconds. A frame is over budget when its
 * build (UI thread) or its raster (raster thread) took longer than one refresh interval, as
 * DevTools counts it.
 *
 * Only profile (and release) timings mean anything: a debug build runs JIT code with asserts on,
 * so its frames are slow by construction. Jank is a problem only in profile; in debug the report
 * carries an INFO note instead [FR4].
 *
 * Pure: no device, no IDE.
 */
object FrameStats {

    /** One `Flutter.Frame`. Times in microseconds. */
    data class Frame(
        val number: Long,
        /** Engine-monotonic µs at build start. */
        val startTimeUs: Long,
        val elapsedUs: Long,
        val buildUs: Long,
        val rasterUs: Long,
        val vsyncOverheadUs: Long,
        /** When the batch carrying it was posted, epoch ms on the device's clock. */
        val postedAtMs: Long,
        val history: Boolean,
    )

    /** p50 / p90 / worst of one phase, in milliseconds. */
    data class Distribution(val p50Ms: Double, val p90Ms: Double, val worstMs: Double)

    data class Report(
        val frames: List<Frame>,
        val budgetMs: Double,
        /** False when no refresh rate was known and [DEFAULT_FPS] was assumed. */
        val budgetMeasured: Boolean,
        val build: Distribution?,
        val raster: Distribution?,
        val overBudget: List<Frame>,
        val problems: List<LikelyProblem>,
    )

    /** Every readable `Flutter.Frame` in [events], in order. */
    fun read(events: List<FlutterExtensionEvent>): List<Frame> =
        events.filter { it.kind == FlutterExtensionEvent.FRAME }.mapNotNull(::read)

    /** Null when a field a verdict needs — number, start, build or raster — is missing. */
    fun read(event: FlutterExtensionEvent): Frame? {
        val data = event.data
        val build = FlutterJson.long(data, "build")
        val raster = FlutterJson.long(data, "raster")
        if (build == null || raster == null) return null
        val number = FlutterJson.long(data, "number") ?: return null
        val start = FlutterJson.long(data, "startTime") ?: return null
        return Frame(
            number = number,
            startTimeUs = start,
            elapsedUs = FlutterJson.long(data, "elapsed") ?: 0,
            buildUs = build,
            rasterUs = raster,
            vsyncOverheadUs = FlutterJson.long(data, "vsyncOverhead") ?: 0,
            postedAtMs = event.timestampMs,
            history = event.history,
        )
    }

    /** `{"type":"DisplayRefreshRate","fps":60.000003814697266}` from `_flutter.getDisplayRefreshRate`. */
    fun refreshRate(result: JsonObject?): Double? =
        FlutterJson.double(result, "fps")?.takeIf { it in MIN_FPS..MAX_FPS }

    fun budgetMs(fps: Double?): Double = MILLIS_PER_SECOND / (fps ?: DEFAULT_FPS)

    fun isOverBudget(frame: Frame, budgetMs: Double): Boolean =
        frame.buildUs / MICROS_PER_MILLI > budgetMs || frame.rasterUs / MICROS_PER_MILLI > budgetMs

    /**
     * @param fps the display's refresh rate, from [refreshRate]; null assumes [DEFAULT_FPS].
     * @param build null when the build mode is unknown, which is treated like debug: no verdict.
     * @param includeHistory frames DDS replayed are app start-up, which is slow by nature; they are
     *   left out unless asked for.
     */
    fun analyse(
        events: List<FlutterExtensionEvent>,
        fps: Double?,
        build: FlutterBuild?,
        includeHistory: Boolean = false,
    ): Report {
        val frames = read(events).filter { includeHistory || !it.history }
        val budget = budgetMs(fps)
        val over = frames.filter { isOverBudget(it, budget) }
        return Report(
            frames = frames,
            budgetMs = budget,
            budgetMeasured = fps != null,
            build = distribution(frames.map { it.buildUs }),
            raster = distribution(frames.map { it.rasterUs }),
            overBudget = over,
            problems = listOfNotNull(problem(frames, over, budget, build)),
        )
    }

    /**
     * Nothing when every frame made its budget. In profile and release, a WARNING only when slow
     * frames are frequent enough to see ([isJanky]); fewer is an INFO with the same numbers. In
     * debug, or an unknown build, always an INFO.
     */
    private fun problem(
        frames: List<Frame>,
        over: List<Frame>,
        budgetMs: Double,
        build: FlutterBuild?,
    ): LikelyProblem? {
        if (over.isEmpty()) return null
        val worst = frames.maxBy { maxOf(it.buildUs, it.rasterUs) }
        val counts = "${over.size} of ${frames.size} frames over the ${ms(budgetMs)} budget; " +
            "worst: build ${ms(worst.buildUs / MICROS_PER_MILLI)}, raster ${ms(worst.rasterUs / MICROS_PER_MILLI)}"
        return when (build) {
            FlutterBuild.PROFILE, FlutterBuild.RELEASE -> if (isJanky(frames.size, over)) {
                LikelyProblem(TYPE, Severity.WARNING, "Janky frames: $counts", over.size, section = FLUTTER_SECTION)
            } else {
                LikelyProblem(
                    TYPE,
                    Severity.INFO,
                    "A few slow frames: $counts",
                    over.size,
                    section = FLUTTER_SECTION,
                )
            }
            FlutterBuild.DEBUG, null -> LikelyProblem(
                TYPE,
                Severity.INFO,
                "${if (build == null) "Build mode unknown" else "Debug build"}: frame times are not " +
                    "representative — $counts. Measure jank in a profile build.",
                over.size,
                section = FLUTTER_SECTION,
            )
        }
    }

    /**
     * Slow frames a user sees: at least [JANK_MIN_FRAMES] of them making up [JANK_MIN_RATIO] of
     * the frames, or a single frozen one. One slow frame in thousands is a hiccup, not jank. The
     * thresholds await device validation (plan H3).
     */
    fun isJanky(frameCount: Int, over: List<Frame>): Boolean {
        // On the frame's whole span, vsync to raster done, as Android vitals measures a frozen frame.
        val frozen = over.any { it.elapsedUs / MICROS_PER_MILLI >= FROZEN_FRAME_MS }
        val frequent = over.size >= JANK_MIN_FRAMES && over.size >= frameCount * JANK_MIN_RATIO
        return frozen || frequent
    }

    /** Nearest-rank percentiles; null for no frames. */
    private fun distribution(valuesUs: List<Long>): Distribution? {
        if (valuesUs.isEmpty()) return null
        val sorted = valuesUs.sorted()
        fun at(percentile: Int): Double {
            val rank = (percentile * sorted.size + PERCENT - 1) / PERCENT
            return sorted[(rank - 1).coerceIn(0, sorted.lastIndex)] / MICROS_PER_MILLI
        }
        return Distribution(p50Ms = at(P50), p90Ms = at(P90), worstMs = sorted.last() / MICROS_PER_MILLI)
    }

    internal fun ms(value: Double): String = String.format(Locale.ROOT, "%.1f ms", value)

    /** The problem type for frame timings, a warning or a note. */
    const val TYPE = FlutterProblemTypes.JANK

    /** Assumed when the app does not answer `_flutter.getDisplayRefreshRate`. */
    const val DEFAULT_FPS = 60.0

    /** Fewer slow frames than this is never jank, however short the recording. */
    const val JANK_MIN_FRAMES = 3

    /** One frame in twenty over budget: three dropped frames a second at 60 Hz, a visible stutter. */
    const val JANK_MIN_RATIO = 0.05

    /** Android vitals' frozen frame: one whose span (`elapsed`) is this long is a visible freeze on its own. */
    const val FROZEN_FRAME_MS = 700.0

    private const val MIN_FPS = 1.0
    private const val MAX_FPS = 1000.0
    private const val MILLIS_PER_SECOND = 1000.0
    private const val MICROS_PER_MILLI = 1000.0
    private const val PERCENT = 100
    private const val P50 = 50
    private const val P90 = 90
}
