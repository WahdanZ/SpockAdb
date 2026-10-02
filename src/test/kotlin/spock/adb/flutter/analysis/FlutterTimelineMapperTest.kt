package spock.adb.flutter.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterBuild
import spock.adb.timeline.TimelineCategory
import spock.adb.timeline.TimelineExport
import spock.adb.timeline.TimelineSeverity
import java.time.ZoneOffset

class FlutterTimelineMapperTest {

    private val placement = FlutterTimelineMapper.Placement(deviceSerial = "emulator-5554", zone = ZoneOffset.UTC)
    private val fps = FrameStats.refreshRate(FlutterFixtures.json("displayRefreshRate.json"))
    private val recorded = listOf(
        FlutterExtensionEvent.ERROR,
        FlutterExtensionEvent.NAVIGATION,
        FlutterExtensionEvent.FRAME,
        FlutterExtensionEvent.REBUILT_WIDGETS,
        "Flutter.FirstFrame",
        "Flutter.ServiceExtensionStateChanged",
    ).flatMap { FlutterFixtures.events(it) }

    @Test
    fun `errors, routes and bursts of slow frames become rows in time order, everything else none`() {
        val events = FlutterTimelineMapper.map(recorded, fps, FlutterBuild.DEBUG, placement)

        assertEquals(5, events.count { it.category == TimelineCategory.FLUTTER_ERROR })
        assertEquals(5, events.count { it.category == TimelineCategory.NAVIGATION })
        // Five slow frames, in three bursts: #2–#3 overlap, #4 is alone, #5–#6 overlap.
        val frames = events.filter { it.category == TimelineCategory.FLUTTER_FRAME }
        assertEquals(3, frames.size)
        assertEquals(
            listOf("Frames #2–#3.", "Frames #4.", "Frames #5–#6."),
            frames.map { it.detail.lineSequence().first() },
        )
        assertTrue(frames.all { it.severity == TimelineSeverity.INFO })
        assertEquals(13, events.size)
        assertEquals(events.sortedBy { it.timeMs }, events)
        assertTrue(events.all { it.deviceSerial == "emulator-5554" })
    }

    @Test
    fun `the caller's clock conversion moves every row, and the device stamp stays`() {
        val shifted = placement.copy(toHostMs = { it + 60_000 })

        val device = FlutterTimelineMapper.map(recorded, fps, FlutterBuild.DEBUG, placement)
        val host = FlutterTimelineMapper.map(recorded, fps, FlutterBuild.DEBUG, shifted)

        assertEquals(device.map { it.timeMs + 60_000 }, host.map { it.timeMs })
        assertEquals(device.map { it.deviceTime }, host.map { it.deviceTime })
    }

    @Test
    fun `replayed frames are start-up and get no row`() {
        val replayed = FlutterFixtures.events(FlutterExtensionEvent.FRAME, history = true)

        assertTrue(FlutterTimelineMapper.map(replayed, fps, FlutterBuild.PROFILE, placement).isEmpty())
    }

    @Test
    fun `a debug build slow on every frame for ten seconds is one row, not six hundred`() {
        val frames = (1..600).map { frame(it, buildUs = 40_000) }

        val rows = FlutterTimelineMapper.map(frames, 60.0, FlutterBuild.DEBUG, placement)

        val row = rows.single()
        assertEquals(TimelineSeverity.INFO, row.severity)
        assertEquals("600 of 600 frames over the 16.7 ms budget; worst build 40.0 ms, raster 2.0 ms", row.title)
        assertTrue(row.detail.contains("Debug build: frame times are not representative"), row.detail)
    }

    @Test
    fun `in a profile build jank between good frames is one burst, and quiet starts the next`() {
        // Every third frame slow for two seconds; then two seconds without frames; then one slow frame.
        val janky = (1..120).map { frame(it, buildUs = if (it % 3 == 0) 30_000L else 4_000L) }
        val later = frame(240, buildUs = 25_000)

        val rows = FlutterTimelineMapper.map(janky + later, 60.0, FlutterBuild.PROFILE, placement)

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.severity == TimelineSeverity.WARNING })
        assertEquals("40 of 118 frames over the 16.7 ms budget; worst build 30.0 ms, raster 2.0 ms", rows[0].title)
        assertEquals("Slow frame: build 25.0 ms, raster 2.0 ms (budget 16.7 ms)", rows[1].title)
    }

    @Test
    fun `a window of many separate bursts is capped, the last row standing for the rest`() {
        // Slow frames two seconds apart: each its own burst.
        val frames = (1..30).map { frame(it * 120, buildUs = 30_000) }

        val rows = FlutterTimelineMapper.map(frames, 60.0, FlutterBuild.PROFILE, placement)

        assertEquals(FlutterTimelineMapper.MAX_FRAME_ROWS, rows.size)
        val last = rows.last()
        assertTrue(last.title.startsWith("11 of "), last.title)
        assertTrue(last.detail.contains("11 bursts in one row"), last.detail)
    }

    @Test
    fun `an error row is the summary, with the rendered report as its detail`() {
        val error = FlutterTimelineMapper.map(recorded, fps, FlutterBuild.DEBUG, placement)
            .first { it.category == TimelineCategory.FLUTTER_ERROR }

        assertEquals(TimelineSeverity.ERROR, error.severity)
        assertEquals(1790887895144, error.timeMs)
        assertTrue(error.title.startsWith("Exception caught by rendering library: A RenderFlex overflowed"))
        assertTrue(error.detail.startsWith("══╡ EXCEPTION CAUGHT BY RENDERING LIBRARY"), error.detail)
        assertFalse(error.detail.contains("Replayed"))
    }

    @Test
    fun `a replayed error says it happened before Spock connected`() {
        val replayed = FlutterFixtures.events(FlutterExtensionEvent.ERROR, history = true).first()

        val row = FlutterTimelineMapper.error(FlutterErrorReader.read(replayed), placement)

        assertTrue(row.detail.startsWith("Replayed on connect"), row.detail)
    }

    @Test
    fun `a route is named without claiming it was opened`() {
        val routes = FlutterFixtures.events(FlutterExtensionEvent.NAVIGATION)
            .mapNotNull { FlutterTimelineMapper.navigation(it, placement) }

        assertEquals(
            listOf("/layout", "/errors", "/network", "/items", "/item/3").map { "Navigator: $it" },
            routes.map { it.title },
        )
        assertEquals(TimelineSeverity.INFO, routes.first().severity)
        assertEquals("10-01 20:51:05.666", routes.first().deviceTime)
        assertEquals("Route: MaterialPageRoute<dynamic>(/layout)", routes.first().detail)

        // removeRoute of the last route posts no route at all.
        val none = FlutterFixtures.event(FlutterExtensionEvent.NAVIGATION, 1, """{"route": null}""")
        assertEquals("Navigator: no route showing", FlutterTimelineMapper.navigation(none, placement)!!.title)
        val unnamed = FlutterFixtures.event(FlutterExtensionEvent.NAVIGATION, 1, """{"route": {}}""")
        assertEquals("Navigator: a route with no name", FlutterTimelineMapper.navigation(unnamed, placement)!!.title)
        assertNull(FlutterTimelineMapper.navigation(FlutterFixtures.events(FlutterExtensionEvent.FRAME).first()))
    }

    @Test
    fun `a route's query string stays on the device`() {
        val deepLink = FlutterFixtures.event(
            FlutterExtensionEvent.NAVIGATION,
            1,
            """{"route": {"description": "MaterialPageRoute<dynamic>(/item/42?ref=spock&email=a@b.c)",
               "settings": {"name": "/item/42?ref=spock&email=a@b.c", "arguments": "secret"}}}""",
        )

        val row = FlutterTimelineMapper.navigation(deepLink, placement)!!

        assertEquals("Navigator: /item/42", row.title)
        assertEquals("Route: MaterialPageRoute<dynamic>(/item/42)", row.detail)
    }

    @Test
    fun `slow frames warn only in a profile build, and can be placed by their own start time`() {
        val frames = listOf(FrameStats.read(FlutterFixtures.events(FlutterExtensionEvent.FRAME)[3])!!)
        val frame = frames.single()
        val budget = FrameStats.budgetMs(fps)

        val debug = FlutterTimelineMapper.slowFrames(frames, budget, FlutterBuild.DEBUG, placement).single()
        assertEquals(TimelineSeverity.INFO, debug.severity)
        assertEquals("Slow frame: build 621.5 ms, raster 46.3 ms (budget 16.7 ms)", debug.title)
        assertEquals(frame.postedAtMs, debug.timeMs)
        assertTrue(debug.detail.contains("batch"), debug.detail)

        val offset = 1_000_000L
        val profile = FlutterTimelineMapper.slowFrames(
            frames,
            budget,
            FlutterBuild.PROFILE,
            placement.copy(frameStartOffsetMs = offset),
        ).single()
        assertEquals(TimelineSeverity.WARNING, profile.severity)
        assertEquals(frame.startTimeUs / 1000 + offset, profile.timeMs)
        assertFalse(profile.detail.contains("batch"))

        val quick = frame.copy(buildUs = 1_000, rasterUs = 2_000)
        assertTrue(FlutterTimelineMapper.slowFrames(listOf(quick), budget, FlutterBuild.PROFILE, placement).isEmpty())
    }

    @Test
    fun `only failed requests become HTTP rows, placed at their start`() {
        val requests = HttpProfileReader.read(FlutterFixtures.json("httpProfile.json")).requests

        val rows = requests.mapNotNull { FlutterTimelineMapper.httpFailure(it, placement) }

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.category == TimelineCategory.HTTP && it.severity == TimelineSeverity.ERROR })
        assertEquals("GET /status/500 (httpbin.org) returned HTTP 500", rows[0].title)
        assertEquals(1790888257230L, rows[0].timeMs)
        assertEquals("10-01 20:57:37.230", rows[0].deviceTime)
        assertEquals("Took 2063 ms.", rows[0].detail)
    }

    @Test
    fun `the rows export like any other`() {
        val rows = FlutterTimelineMapper.map(recorded, fps, FlutterBuild.DEBUG, placement)

        val text = TimelineExport.format(rows)

        assertTrue(text.contains("Navigator: /item/3"), text)
        assertTrue(text.contains(" Flutter    Exception caught by rendering library"), text)
    }

    private fun frame(number: Int, buildUs: Long, rasterUs: Long = 2_000) = FlutterFixtures.event(
        FlutterExtensionEvent.FRAME,
        1_000L + number,
        """{"number": $number, "startTime": ${number * 16_667L}, "elapsed": ${buildUs + rasterUs},
           "build": $buildUs, "raster": $rasterUs, "vsyncOverhead": 100}""",
    )
}
