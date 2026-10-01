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
    fun `errors, routes and slow frames become rows in time order, everything else none`() {
        val events = FlutterTimelineMapper.map(recorded, fps, FlutterBuild.DEBUG, placement)

        assertEquals(5, events.count { it.category == TimelineCategory.FLUTTER_ERROR })
        assertEquals(5, events.count { it.category == TimelineCategory.NAVIGATION })
        assertEquals(5, events.count { it.category == TimelineCategory.FLUTTER_FRAME })
        assertEquals(15, events.size)
        assertEquals(events.sortedBy { it.timeMs }, events)
        assertTrue(events.all { it.deviceSerial == "emulator-5554" })
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
        assertTrue(routes.first().detail.startsWith("Route: MaterialPageRoute<dynamic>(/layout)"))

        val unnamed = FlutterFixtures.event(FlutterExtensionEvent.NAVIGATION, 1, """{"route": null}""")
        assertEquals("Navigator: a route with no name", FlutterTimelineMapper.navigation(unnamed, placement)!!.title)
        assertNull(FlutterTimelineMapper.navigation(FlutterFixtures.events(FlutterExtensionEvent.FRAME).first()))
    }

    @Test
    fun `slow frames warn only in a profile build, and can be placed by their own start time`() {
        val frame = FrameStats.read(FlutterFixtures.events(FlutterExtensionEvent.FRAME)[3])!!
        val budget = FrameStats.budgetMs(fps)

        val debug = FlutterTimelineMapper.jankFrame(frame, budget, FlutterBuild.DEBUG, placement)!!
        assertEquals(TimelineSeverity.INFO, debug.severity)
        assertEquals("Slow frame #5: build 621.5 ms, raster 46.3 ms (budget 16.7 ms)", debug.title)
        assertEquals(frame.postedAtMs, debug.timeMs)

        val offset = 1_000_000L
        val profile = FlutterTimelineMapper.jankFrame(
            frame,
            budget,
            FlutterBuild.PROFILE,
            placement.copy(frameStartOffsetMs = offset),
        )!!
        assertEquals(TimelineSeverity.WARNING, profile.severity)
        assertEquals(frame.startTimeUs / 1000 + offset, profile.timeMs)
        assertFalse(profile.detail.contains("batch"))

        val quick = frame.copy(buildUs = 1_000, rasterUs = 2_000)
        assertNull(FlutterTimelineMapper.jankFrame(quick, budget, FlutterBuild.PROFILE, placement))
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
}
