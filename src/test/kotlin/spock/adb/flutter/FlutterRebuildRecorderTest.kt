package spock.adb.flutter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.CancellationSignal
import spock.adb.flutter.FlutterRebuildRecorder.Companion.LOCATION_MAP
import spock.adb.flutter.FlutterRebuildRecorder.Companion.TRACK_REBUILDS
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent

/**
 * The rebuild window writes to the app, so what it writes is the point: the shared tracking flag
 * is switched on only when off, and off only when Spock switched it on [FR10]; nothing at all on a
 * read-only connection, in a build without the inspector, or when the flag cannot be read.
 */
class FlutterRebuildRecorderTest : FlutterSessionFixture() {

    @Volatile
    private var tracking = "false"

    private fun serveTracking() = vm.on(TRACK_REBUILDS) { params ->
        params.get("enabled")?.let { tracking = it.asString }
        FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", tracking) })
    }

    private fun serveLocations() = vm.on(LOCATION_MAP) {
        FakeVmService.Reply.Result(
            JsonObject().apply {
                addProperty("type", "_extensionType")
                add(
                    "result",
                    JsonObject().apply {
                        add(
                            "file:///app/lib/fixtures/frames.dart",
                            JsonObject().apply {
                                add("ids", JsonArray().apply { add(7) })
                                add("lines", JsonArray().apply { add(40) })
                                add("columns", JsonArray().apply { add(12) })
                                add("names", JsonArray().apply { add("StormTile") })
                            },
                        )
                    },
                )
            },
        )
    }

    /** One frame in which location 7 was built [count] times — named only by the seed. */
    private fun pushRebuilt(count: Int, frame: Int) {
        vm.pushEvent(
            "Extension",
            isolateEvent("Extension", UI_ISOLATE, connectedAt + 1_000_000 + frame) {
                addProperty("extensionKind", "Flutter.RebuiltWidgets")
                add(
                    "extensionData",
                    JsonObject().apply {
                        addProperty("startTime", 1_000_000L + frame * 16_000L)
                        add("events", JsonArray().apply { listOf(7, count).forEach(::add) })
                    },
                )
            },
        )
    }

    /** A recorder whose window delivers two frames, then [during], then ends. */
    private fun recorder(during: () -> Unit = {}): FlutterRebuildRecorder {
        var first = true
        return FlutterRebuildRecorder(
            session,
            sleep = { ms ->
                if (first) {
                    first = false
                    pushRebuilt(count = 3, frame = 1)
                    pushRebuilt(count = 2, frame = 2)
                    awaitEventsHandled()
                    during()
                }
                Thread.sleep(minOf(ms, 20))
            },
        )
    }

    private fun writes() = writes(TRACK_REBUILDS)

    @Test
    fun `a flag that was off is switched on for the window and off again, and the window is counted`() {
        serveTracking()
        serveLocations()
        session.connect(pasted())

        val result = recorder().record(windowMs = 150, cancelled = { false })

        val recorded = result as FlutterRebuildRecorder.Result.Recorded
        assertEquals(listOf("true", "false"), writes())
        assertEquals("false", tracking)
        assertEquals(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF, recorded.tracking)
        assertTrue(recorded.seeded)
        assertEquals(2, recorded.report.frames)
        val top = recorded.report.top.single()
        assertEquals(5L, top.rebuilds)
        assertEquals("StormTile at lib/fixtures/frames.dart:40:12", top.label)
    }

    @Test
    fun `a flag that was already on, the IDE's, is never written`() {
        tracking = "true"
        serveTracking()
        serveLocations()
        session.connect(pasted())

        val recorded = recorder().record(windowMs = 100, cancelled = { false })
            as FlutterRebuildRecorder.Result.Recorded

        assertEquals(emptyList<String>(), writes())
        assertEquals("true", tracking)
        assertEquals(FlutterRebuildRecorder.Tracking.ALREADY_ON, recorded.tracking)
        assertEquals(2, recorded.report.frames)
    }

    @Test
    fun `a read-only direct connection is refused before anything is asked`() {
        vm.dds = false
        serveTracking()
        session.connect(pasted(), allowDirect = true)

        val result = recorder().record(windowMs = 100, cancelled = { false })

        assertEquals(FlutterRebuildRecorder.Result.Refused(FlutterRebuildRecorder.READ_ONLY), result)
        assertEquals(0, extensionCalls(TRACK_REBUILDS).size)
    }

    @Test
    fun `a build without the inspector is refused`() {
        vm.addIsolate(UI_ISOLATE, listOf("ext.flutter.reassemble", "ext.flutter.exit"))
        serveTracking()
        session.connect(pasted())

        val result = recorder().record(windowMs = 100, cancelled = { false })

        assertEquals(FlutterRebuildRecorder.Result.Refused(FlutterRebuildRecorder.NOT_DEBUG), result)
        assertEquals(0, extensionCalls(TRACK_REBUILDS).size)
    }

    @Test
    fun `a flag that cannot be read is left alone`() {
        vm.on(TRACK_REBUILDS) { FakeVmService.Reply.Error(-32_000, "Server error") }
        session.connect(pasted())

        val result = recorder().record(windowMs = 100, cancelled = { false })

        assertTrue(result is FlutterRebuildRecorder.Result.Refused, "$result")
        assertEquals(emptyList<String>(), writes())
    }

    @Test
    fun `a session that ends during the window says the flag was left on`() {
        serveTracking()
        serveLocations()
        session.connect(pasted())

        val recorded = recorder(during = { session.close() })
            .record(windowMs = 100, cancelled = { false }) as FlutterRebuildRecorder.Result.Recorded

        assertEquals(listOf("true"), writes())
        assertEquals(FlutterRebuildRecorder.Tracking.LEFT_ON, recorded.tracking)
    }

    @Test
    fun `a second window on the same session is refused while the first runs, and the first still restores`() {
        serveTracking()
        serveLocations()
        session.connect(pasted())
        var second: FlutterRebuildRecorder.Result? = null

        val first = recorder(during = { second = FlutterRebuildRecorder(session).record(100, { false }) })
            .record(windowMs = 100, cancelled = { false })

        assertEquals(FlutterRebuildRecorder.Result.Refused(FlutterRebuildRecorder.ALREADY_RECORDING), second)
        val tracking = (first as FlutterRebuildRecorder.Result.Recorded).tracking
        assertEquals(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF, tracking)
        assertEquals(listOf("true", "false"), writes())
    }

    @Test
    fun `a cancelled window ends early and still switches the flag off`() {
        serveTracking()
        serveLocations()
        session.connect(pasted())
        var cancel = false
        val signal = CancellationSignal { cancel }

        val recorded = recorder(during = { cancel = true })
            .record(windowMs = 60_000, cancelled = signal) as FlutterRebuildRecorder.Result.Recorded

        assertTrue(recorded.windowMs < 60_000, "${recorded.windowMs}")
        assertEquals(listOf("true", "false"), writes())
    }
}
