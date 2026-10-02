package spock.adb.flutter.analysis

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.LikelyProblem.Severity

/**
 * Against five `Flutter.RebuiltWidgets` events recorded on the sample app's item list: the first
 * (from the forced rebuild that switching tracking on causes) describes every location, the rest
 * only count.
 */
class RebuildTrackerTest {

    private val events = FlutterFixtures.events(FlutterExtensionEvent.REBUILT_WIDGETS)

    @Test
    fun `counts add up across frames and are named from the locations sent once`() {
        val report = RebuildTracker().apply { acceptAll(events) }.report()

        assertEquals(5, report.frames)
        // From the first frame's start to the last's: 364016652 − 354799985 µs.
        assertEquals(9_216L, report.windowMs)
        val top = report.top.first()
        assertEquals(203, top.id)
        assertEquals(19, top.rebuilds)
        assertEquals("ListTile at lib/fixtures/routes.dart:18:18", top.label)
        assertEquals(19 / 9.216, top.perSecond!!, 0.01)
        assertEquals(3.8, top.perFrame, 0.001)
        assertEquals(listOf(203, 204, 127, 141), report.top.take(4).map { it.id })
        assertEquals(RebuildTracker.DEFAULT_LIMIT, report.top.size)
        // Five frames is a tap, not a storm.
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `the caller's window wins over the frames' own span`() {
        val report = RebuildTracker().apply { acceptAll(events) }.report(windowMs = 19_000, limit = 2)

        assertEquals(1.0, report.top.first().perSecond!!, 0.001)
        assertEquals(2, report.top.size)
    }

    @Test
    fun `ids from before the window are reported by number until the full map is seeded`() {
        val late = RebuildTracker().apply { acceptAll(events.drop(1)) }
        assertEquals("Widget location #92", late.report().top.first { it.id == 92 }.label)

        late.seedLocations(events.first().data.getAsJsonObject("locations"))
        assertEquals("Scaffold at lib/fixtures/common.dart:13:12", late.report().top.first { it.id == 92 }.label)
    }

    @Test
    fun `the older newLocations encoding alone still locates widgets, without names`() {
        val tracker = RebuildTracker()
        val legacy = events.first().data.deepCopy().apply { remove("locations") }
        tracker.accept(events.first().copy(data = legacy))

        val report = tracker.report()
        assertEquals("Widget at lib/fixtures/routes.dart:18:18", report.top.first { it.id == 203 }.label)
    }

    @Test
    fun `a widget rebuilt in every frame for a second is a rebuild storm`() {
        val tracker = RebuildTracker()
        tracker.accept(
            frame(0, """[7, 1, 8, 3]""", """"newLocations": {"file:///p/lib/clock.dart": [7, 12, 5, 8, 20, 9]}"""),
        )
        (1 until RebuildTracker.MIN_RUN_FRAMES).forEach { tracker.accept(frame(it, "[7, 1]")) }

        val report = tracker.report(windowMs = 1_000)
        val hint = report.problems.single()
        assertEquals(Severity.INFO, hint.severity)
        assertEquals("frequentRebuilds", hint.type)
        assertEquals(
            "Widget at lib/clock.dart:12:5 rebuilt 60 times in 60 frames, in every one of 60 frames in a row " +
                "(60/s, 1.0 per frame)",
            hint.summary,
        )
        assertEquals(60, hint.count)
        assertEquals(60, report.top.first().longestRun)
        // Built three times in one frame and never again: nothing to report.
        assertEquals(8, report.top[1].id)
    }

    @Test
    fun `items scrolling in, several a frame but not every frame, are not reported`() {
        val tracker = RebuildTracker()
        // A fling: three new list items every other frame for four seconds, 1.5 a frame on average.
        (0 until 240).forEach { tracker.accept(frame(it, if (it % 2 == 0) "[5, 3]" else "[]")) }
        // A run one frame short of the threshold, twice.
        (240 until 299).forEach { tracker.accept(frame(it, "[6, 1]")) }
        tracker.accept(frame(299, "[]"))
        (300 until 359).forEach { tracker.accept(frame(it, "[6, 1]")) }

        val report = tracker.report()

        assertTrue(report.problems.isEmpty(), "${report.problems}")
        assertEquals(1.0, report.top.first { it.id == 5 }.perFrame, 0.01)
        assertEquals(RebuildTracker.MIN_RUN_FRAMES - 1, report.top.first { it.id == 6 }.longestRun)
    }

    @Test
    fun `a list's worth of widgets rebuilt on every frame is a warning`() {
        val tracker = RebuildTracker()
        (0 until RebuildTracker.MIN_RUN_FRAMES).forEach { tracker.accept(frame(it, "[9, 12]")) }

        assertEquals(Severity.WARNING, tracker.report().problems.single().severity)
    }

    @Test
    fun `a hot restart's new isolate starts the window over, and replayed frames are left out`() {
        val tracker = RebuildTracker()
        tracker.acceptAll(events)
        tracker.accept(frame(1, "[203, 50]").copy(history = true))
        assertEquals(19, tracker.report().top.first().rebuilds)

        // The new isolate numbers locations again: 203 is something else there, and unnamed so far.
        val restarted = frame(2, "[203, 2]").copy(isolateId = "isolates/2")
        tracker.accept(restarted)

        val report = tracker.report()
        assertEquals(1, report.frames)
        assertEquals(2, report.top.single().rebuilds)
        assertEquals("Widget location #203", report.top.single().label)

        // Seeding from the new isolate names it; seeding from the old one would start over again.
        tracker.seedLocations(
            JsonParser.parseString("""{"file:///p/lib/a.dart": {"ids": [203], "lines": [4], "columns": [2]}}""")
                .asJsonObject,
            isolateId = "isolates/2",
        )
        assertEquals("Widget at lib/a.dart:4:2", tracker.report().top.single().label)
    }

    @Test
    fun `reports can be read while events arrive on another thread`() {
        val tracker = RebuildTracker()
        val feeder = Thread { (0 until 5_000).forEach { tracker.accept(frame(it, "[${it % 50}, 1]")) } }
        feeder.start()
        repeat(200) { tracker.report() }
        feeder.join()

        assertEquals(5_000, tracker.report().frames)
    }

    @Test
    fun `no events, empty frames and malformed payloads never throw`() {
        assertTrue(RebuildTracker().report().top.isEmpty())

        val tracker = RebuildTracker()
        tracker.accept(frame(0, "[]"))
        tracker.accept(FlutterFixtures.event(FlutterExtensionEvent.REBUILT_WIDGETS, 1, """{"events": "x"}"""))
        tracker.accept(FlutterFixtures.event(FlutterExtensionEvent.REBUILT_WIDGETS, 2, """{"events": [1, "a", 2]}"""))
        tracker.accept(
            FlutterFixtures.event(
                FlutterExtensionEvent.REBUILT_WIDGETS,
                3,
                """{"locations": {"f": {"ids": [1, 2], "lines": [3]}, "g": 5}, "newLocations": {"h": [1, 2]}}""",
            ),
        )
        tracker.accept(FlutterFixtures.event(FlutterExtensionEvent.FRAME, 4, """{"events": [9, 9]}"""))
        tracker.seedLocations(JsonParser.parseString("""{"f": []}""").asJsonObject)

        val report = tracker.report()
        assertEquals(4, report.frames)
        assertTrue(report.top.isEmpty())
        assertNull(report.windowMs)
    }

    private fun frame(index: Int, counts: String, extra: String = ""): FlutterExtensionEvent {
        val more = if (extra.isEmpty()) "" else ", $extra"
        return FlutterFixtures.event(
            FlutterExtensionEvent.REBUILT_WIDGETS,
            1_000L + index * 16,
            """{"startTime": ${index * 16_667L}, "frameNumber": $index, "events": $counts$more}""",
        )
    }
}
