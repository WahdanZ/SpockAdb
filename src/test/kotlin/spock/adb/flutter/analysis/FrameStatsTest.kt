package spock.adb.flutter.analysis

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.flutter.FlutterBuild

/**
 * Against five `Flutter.Frame` events from a debug build on an emulator — every one of them far
 * over budget, which is exactly why debug frame times must not be reported as jank.
 */
class FrameStatsTest {

    private val events = FlutterFixtures.events(FlutterExtensionEvent.FRAME)
    private val fps = FrameStats.refreshRate(FlutterFixtures.json("displayRefreshRate.json"))

    @Test
    fun `the budget comes from the display's refresh rate`() {
        assertEquals(60.000003814697266, fps)
        assertEquals(16.67, FrameStats.budgetMs(fps), 0.01)
        assertEquals(16.67, FrameStats.budgetMs(null), 0.01)
        assertEquals(8.33, FrameStats.budgetMs(120.0), 0.01)
    }

    @Test
    fun `a refresh rate that is missing or absurd is not used`() {
        assertNull(FrameStats.refreshRate(null))
        assertNull(FrameStats.refreshRate(FlutterFixtures.json("listViews.json")))
        assertNull(FrameStats.refreshRate(JsonParser.parseString("""{"fps": 0}""").asJsonObject))
        assertNull(FrameStats.refreshRate(JsonParser.parseString("""{"fps": "60"}""").asJsonObject))
    }

    @Test
    fun `percentiles are per phase, in milliseconds`() {
        val report = FrameStats.analyse(events, fps, FlutterBuild.PROFILE)

        assertEquals(5, report.frames.size)
        assertTrue(report.budgetMeasured)
        val build = report.build!!
        assertEquals(126.828, build.p50Ms, 0.001)
        assertEquals(621.542, build.p90Ms, 0.001)
        assertEquals(621.542, build.worstMs, 0.001)
        val raster = report.raster!!
        assertEquals(221.722, raster.p50Ms, 0.001)
        assertEquals(1936.821, raster.worstMs, 0.001)
        assertEquals(5, report.overBudget.size)
    }

    @Test
    fun `slow frames are a warning in a profile build`() {
        val problem = FrameStats.analyse(events, fps, FlutterBuild.PROFILE).problems.single()

        assertEquals(Severity.WARNING, problem.severity)
        assertEquals("jank", problem.type)
        assertEquals(
            "Janky frames: 5 of 5 frames over the 16.7 ms budget; worst: build 0.0 ms, raster 1936.8 ms",
            problem.summary,
        )
        assertEquals(5, problem.count)
    }

    @Test
    fun `in a debug build they are only a note that debug timings mean nothing`() {
        val problem = FrameStats.analyse(events, fps, FlutterBuild.DEBUG).problems.single()

        assertEquals(Severity.INFO, problem.severity)
        assertTrue(problem.summary.startsWith("Debug build: frame times are not representative"), problem.summary)
        assertTrue(problem.summary.endsWith("Measure jank in a profile build."), problem.summary)

        val unknown = FrameStats.analyse(events, fps, build = null).problems.single()
        assertEquals(Severity.INFO, unknown.severity)
        assertTrue(unknown.summary.startsWith("Build mode unknown"), unknown.summary)
    }

    @Test
    fun `frames within budget in a profile build are no problem`() {
        val smooth = listOf(frame(number = 1, buildUs = 4_000, rasterUs = 6_000), frame(2, 5_000, 16_000))
        val report = FrameStats.analyse(smooth, fps = 60.0, build = FlutterBuild.PROFILE)

        assertTrue(report.problems.isEmpty())
        assertTrue(report.overBudget.isEmpty())
        // On a 120 Hz display the second frame misses its 8.3 ms budget.
        assertEquals(1, FrameStats.analyse(smooth, fps = 120.0, build = FlutterBuild.PROFILE).overBudget.size)
    }

    @Test
    fun `smooth frames are no note even in a debug build`() {
        val smooth = (1L..10L).map { frame(it, 4_000, 6_000) }

        assertTrue(FrameStats.analyse(smooth, 60.0, FlutterBuild.DEBUG).problems.isEmpty())
        assertTrue(FrameStats.analyse(smooth, 60.0, build = null).problems.isEmpty())
    }

    @Test
    fun `one slow frame in a thousand is a note, not jank`() {
        val mostlySmooth = (1L..1_000L).map { frame(it, if (it == 500L) 30_000 else 4_000, 6_000) }

        val problem = FrameStats.analyse(mostlySmooth, 60.0, FlutterBuild.PROFILE).problems.single()

        assertEquals(Severity.INFO, problem.severity)
        assertTrue(problem.summary.startsWith("A few slow frames: 1 of 1000 frames"), problem.summary)
    }

    @Test
    fun `jank is frequent slow frames, or one frozen frame`() {
        // 60 of 1000 is 6 %: over the 5 % line.
        val frequent = (1L..1_000L).map { frame(it, if (it % 50 < 3) 30_000 else 4_000, 6_000) }
        assertEquals(Severity.WARNING, severity(frequent, FlutterBuild.PROFILE))

        // 40 of 1000 is 4 %: under it.
        val rare = (1L..1_000L).map { frame(it, if (it % 50 < 2) 30_000 else 4_000, 6_000) }
        assertEquals(Severity.INFO, severity(rare, FlutterBuild.PROFILE))

        val frozen = (1L..1_000L).map { frame(it, if (it == 500L) 800_000 else 4_000, 6_000) }
        assertEquals(Severity.WARNING, severity(frozen, FlutterBuild.RELEASE))
    }

    private fun severity(events: List<FlutterExtensionEvent>, build: FlutterBuild) =
        FrameStats.analyse(events, 60.0, build).problems.single().severity

    @Test
    fun `replayed start-up frames are left out unless asked for`() {
        val history = FlutterFixtures.events(FlutterExtensionEvent.FRAME, history = true)

        val report = FrameStats.analyse(history, fps, FlutterBuild.PROFILE)
        assertTrue(report.frames.isEmpty())
        assertNull(report.build)
        assertTrue(report.problems.isEmpty())

        assertEquals(5, FrameStats.analyse(history, fps, FlutterBuild.PROFILE, includeHistory = true).frames.size)
    }

    @Test
    fun `no frames, or unreadable ones, are no frames`() {
        assertTrue(FrameStats.analyse(emptyList(), fps, FlutterBuild.DEBUG).problems.isEmpty())

        val odd = listOf(
            """{}""",
            """{"number": "1", "startTime": 1, "build": 1, "raster": 1}""",
            """{"number": 1, "startTime": 1, "build": [], "raster": 1}""",
        ).mapIndexed { index, data -> FlutterFixtures.event(FlutterExtensionEvent.FRAME, index.toLong(), data) }
        assertTrue(FrameStats.analyse(odd, fps, FlutterBuild.PROFILE).frames.isEmpty())
    }

    @Test
    fun `over budget means build or raster, not the total span`() {
        val budget = FrameStats.budgetMs(60.0)

        assertTrue(FrameStats.isOverBudget(FrameStats.read(frame(1, 17_000, 1_000))!!, budget))
        assertTrue(FrameStats.isOverBudget(FrameStats.read(frame(1, 1_000, 17_000))!!, budget))
        // Long total because of vsync wait, both phases quick: not jank.
        assertFalse(FrameStats.isOverBudget(FrameStats.read(frame(1, 8_000, 8_000, elapsedUs = 40_000))!!, budget))
    }

    private fun frame(number: Long, buildUs: Long, rasterUs: Long, elapsedUs: Long = buildUs + rasterUs) =
        FlutterFixtures.event(
            FlutterExtensionEvent.FRAME,
            1_000L + number,
            """{"number": $number, "startTime": ${number * 16_667}, "elapsed": $elapsedUs,
               "build": $buildUs, "raster": $rasterUs, "vsyncOverhead": 100}""",
        )
}
