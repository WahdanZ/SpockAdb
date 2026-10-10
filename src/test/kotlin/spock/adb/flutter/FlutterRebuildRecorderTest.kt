package spock.adb.flutter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.CancellationSignal
import spock.adb.flutter.FlutterRebuildRecorder.Companion.LOCATION_MAP
import spock.adb.flutter.FlutterRebuildRecorder.Companion.TRACK_REBUILDS
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * The rebuild window writes to the app, so what it writes is the point: the shared tracking flag
 * is switched on only when off, and off only when Spock switched it on and nobody else wrote it
 * [FR10]; nothing at all on a read-only connection, in a build without the inspector, or when the
 * flag cannot be read. The fake app behaves as Flutter's inspector does: switching tracking on
 * rebuilds the whole tree once, and every write is announced after it.
 */
class FlutterRebuildRecorderTest : FlutterSessionFixture() {

    @Volatile
    private var tracking = "false"

    /** Event stamps, each one new: the session takes two identical events for one replayed twice. */
    private val stamps = AtomicLong(connectedAt + 1_000_000)

    /** The read, as the app answers it; [onRead] runs before the answer. */
    private fun serveTracking(onRead: () -> Unit = {}) = vm.on(TRACK_REBUILDS) { params ->
        val enabled = params.get("enabled")?.asString
        if (enabled == null) {
            onRead()
        } else {
            // Flutter's setter forces a rebuild of the whole tree when tracking goes on, then announces the write.
            if (enabled == "true" && tracking == "false") pushRebuilt(count = FORCED_BUILDS)
            tracking = enabled
            vm.pushEvent("Extension", trackingChanged(enabled))
        }
        FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", tracking) })
    }

    private fun trackingChanged(value: String) = isolateEvent("Extension", UI_ISOLATE, stamps.incrementAndGet()) {
        addProperty("extensionKind", "Flutter.ServiceExtensionStateChanged")
        add(
            "extensionData",
            JsonObject().apply {
                addProperty("extension", TRACK_REBUILDS)
                addProperty("value", value)
            },
        )
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

    /** One frame on [isolateId] in which location 7 was built [count] times — named only by the seed. */
    private fun pushRebuilt(count: Int, isolateId: String = UI_ISOLATE) {
        val stamp = stamps.incrementAndGet()
        vm.pushEvent(
            "Extension",
            isolateEvent("Extension", isolateId, stamp) {
                addProperty("extensionKind", "Flutter.RebuiltWidgets")
                add(
                    "extensionData",
                    JsonObject().apply {
                        addProperty("startTime", stamp * 1_000)
                        add("events", JsonArray().apply { listOf(7, count).forEach(::add) })
                    },
                )
            },
        )
    }

    /** A recorder whose window delivers [frames] frames, then [during], then runs out its time. */
    private fun recorder(frames: List<Int> = listOf(3, 2), during: () -> Unit = {}): FlutterRebuildRecorder {
        var first = true
        return FlutterRebuildRecorder(
            session,
            sleep = { ms ->
                if (first) {
                    first = false
                    frames.forEach { pushRebuilt(count = it) }
                    awaitEventsHandled()
                    during()
                }
                Thread.sleep(minOf(ms, 20))
            },
        )
    }

    private fun writes() = writes(TRACK_REBUILDS)

    private fun connected() {
        serveLocations()
        session.connect(pasted())
    }

    @Test
    fun `a flag that was off is switched on for the window and off again, and the window is counted`() {
        serveTracking()
        connected()

        val result = recorder().record(windowMs = 150, cancelled = { false })

        val recorded = result as FlutterRebuildRecorder.Result.Recorded
        assertEquals(listOf("true", "false"), writes())
        assertEquals("false", tracking)
        assertEquals(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF, recorded.tracking)
        assertTrue(recorded.seeded)
        assertFalse(recorded.sessionEnded)
        // The frame the switch forced is not the app's: two frames, five builds.
        assertEquals(2, recorded.report.frames)
        val top = recorded.report.top.single()
        assertEquals(5L, top.rebuilds)
        assertEquals("StormTile at lib/fixtures/frames.dart:40:12", top.label)
    }

    @Test
    fun `an idle screen after switching tracking on reports no frame, the forced rebuild left out`() {
        serveTracking()
        connected()

        val recorded = recorder(frames = emptyList()).record(windowMs = 100, cancelled = { false })
            as FlutterRebuildRecorder.Result.Recorded

        assertEquals(0, recorded.report.frames)
        assertEquals(emptyList<Any>(), recorded.report.top)
        assertEquals(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF, recorded.tracking)
    }

    @Test
    fun `a flag that was already on, the IDE's, is never written`() {
        tracking = "true"
        serveTracking()
        connected()

        val recorded = recorder().record(windowMs = 100, cancelled = { false })
            as FlutterRebuildRecorder.Result.Recorded

        assertEquals(emptyList<String>(), writes())
        assertEquals("true", tracking)
        assertEquals(FlutterRebuildRecorder.Tracking.ALREADY_ON, recorded.tracking)
        assertEquals(2, recorded.report.frames)
    }

    @Test
    fun `the IDE switching tracking on during the window keeps Spock from switching it off`() {
        serveTracking()
        connected()

        val recorded = recorder(
            during = {
                // The IDE's "Show widget rebuild information": the app announces its write too.
                vm.pushEvent("Extension", trackingChanged("true"))
                awaitEventsHandled()
            },
        ).record(windowMs = 100, cancelled = { false }) as FlutterRebuildRecorder.Result.Recorded

        assertEquals(listOf("true"), writes())
        assertEquals("true", tracking)
        assertEquals(FlutterRebuildRecorder.Tracking.CHANGED_BY_OTHERS, recorded.tracking)
    }

    @Test
    fun `the IDE switching tracking on between Spock's read and its write is seen too`() {
        var reads = 0
        serveTracking(
            onRead = {
                // Spock read "off"; the IDE's write lands before Spock's.
                if (reads++ == 0) {
                    vm.pushEvent("Extension", trackingChanged("true"))
                }
            },
        )
        connected()

        val recorded = recorder().record(windowMs = 100, cancelled = { false })
            as FlutterRebuildRecorder.Result.Recorded

        assertEquals(FlutterRebuildRecorder.Tracking.CHANGED_BY_OTHERS, recorded.tracking)
        assertEquals(listOf("true"), writes())
        assertEquals("true", tracking)
    }

    /**
     * An app whose switch-on lands but is never answered — the call times out — and whose reads
     * after the first answer only when [readBackAnswers].
     */
    private fun serveUnansweredSwitch(readBackAnswers: Boolean) {
        var reads = 0
        vm.on(TRACK_REBUILDS) { params ->
            val enabled = params.get("enabled")?.asString
            when {
                enabled == "true" -> {
                    pushRebuilt(count = FORCED_BUILDS)
                    tracking = "true"
                    vm.pushEvent("Extension", trackingChanged("true"))
                    FakeVmService.Reply.None
                }
                enabled != null -> {
                    tracking = enabled
                    vm.pushEvent("Extension", trackingChanged(enabled))
                    FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", tracking) })
                }
                reads++ == 0 || readBackAnswers ->
                    FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", tracking) })
                else -> FakeVmService.Reply.Error(-32_000, "Server error")
            }
        }
    }

    @Test
    fun `a switch-on with no answer that a read shows landed is Spock's, and switched off`() {
        serveUnansweredSwitch(readBackAnswers = true)
        connected()

        val recorded = recorder().record(windowMs = 100, cancelled = { false })
            as FlutterRebuildRecorder.Result.Recorded

        assertEquals(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF, recorded.tracking)
        assertEquals(listOf("true", "false"), writes())
        assertEquals(2, recorded.report.frames)
    }

    @Test
    fun `a switch-on with no answer and no read after is not Spock's to undo, and is said to be unconfirmed`() {
        serveUnansweredSwitch(readBackAnswers = false)
        connected()

        val recorded = recorder().record(windowMs = 100, cancelled = { false })
            as FlutterRebuildRecorder.Result.Recorded

        assertEquals(FlutterRebuildRecorder.Tracking.UNCONFIRMED, recorded.tracking)
        assertEquals(listOf("true"), writes())
        assertEquals("true", tracking)
        // Spock closing the session afterwards does not switch it off either.
        session.close()
        assertEquals(listOf("true"), writes())
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
    fun `an isolate that pauses before the switch is not written to, and nothing is switched off`() {
        serveTracking(
            onRead = {
                vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, stamps.incrementAndGet()))
                eventually(message = "the session to see the pause") { session.state is SessionState.Paused }
            },
        )
        connected()

        val result = recorder().record(windowMs = 100, cancelled = { false })

        assertTrue(result is FlutterRebuildRecorder.Result.Refused, "$result")
        assertTrue((result as FlutterRebuildRecorder.Result.Refused).reason.contains("changed nothing"), result.reason)
        assertEquals(emptyList<String>(), writes())
    }

    @Test
    fun `Spock closing the session during the window switches the flag off as it closes, and ends the window`() {
        serveTracking()
        connected()

        val recorded = recorder(during = { session.close() })
            .record(windowMs = 60_000, cancelled = { false }) as FlutterRebuildRecorder.Result.Recorded

        assertEquals(listOf("true", "false"), writes())
        assertEquals("false", tracking)
        assertEquals(FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF, recorded.tracking)
        assertTrue(recorded.sessionEnded)
        assertTrue(recorded.windowMs < 60_000, "${recorded.windowMs}")
        assertEquals(2, recorded.report.frames)
    }

    @Test
    fun `a connection lost during the window says the flag was left on`() {
        serveTracking()
        connected()

        val recorded = recorder(
            during = {
                vm.server.drop()
                eventually { session.state is SessionState.Disconnected }
            },
        ).record(windowMs = 60_000, cancelled = { false }) as FlutterRebuildRecorder.Result.Recorded

        assertEquals(listOf("true"), writes())
        assertEquals(FlutterRebuildRecorder.Tracking.LEFT_ON, recorded.tracking)
        assertTrue(recorded.sessionEnded)
        assertTrue(recorded.windowMs < 60_000, "${recorded.windowMs}")
    }

    /** A window on [session], owned across sessions, whose connection is lost mid-window: tracking stays on. */
    private fun leftOnByLostWindow(owners: RebuildFlagOwners) {
        session.rebuildOwners = owners
        session.ownerSerial = SERIAL
        serveTracking()
        connected()
        val recorded = recorder(
            during = {
                vm.server.drop()
                eventually { session.state is SessionState.Disconnected }
            },
        ).record(windowMs = 60_000, cancelled = { false }) as FlutterRebuildRecorder.Result.Recorded
        assertEquals(FlutterRebuildRecorder.Tracking.LEFT_ON, recorded.tracking)
        assertEquals("true", tracking)
    }

    /** The next session on the same process, as `flutter attach` gives one, sharing [owners]. */
    private fun nextSession(owners: RebuildFlagOwners) = FlutterSession(clock = { connectedAt }).apply {
        rebuildOwners = owners
        ownerSerial = SERIAL
    }

    @Test
    fun `tracking a lost window left on is switched off by the next session on the process, and it says so`() {
        val owners = RebuildFlagOwners()
        leftOnByLostWindow(owners)
        assertEquals(1, owners.ofProcess(SERIAL, PID).size)

        val next = nextSession(owners)
        try {
            next.connect(pasted())
            eventually(message = "switched off") { next.snapshot.rebuildFlagRestore == RebuildFlagRestore.SWITCHED_OFF }
            eventually(message = "owed no more") { owners.ofProcess(SERIAL, PID).isEmpty() }
        } finally {
            next.close()
        }

        assertEquals(listOf("true", "false"), writes())
        assertEquals("false", tracking)
    }

    @Test
    fun `tracking another tool wrote after the lost window is left as it is`() {
        val owners = RebuildFlagOwners()
        leftOnByLostWindow(owners)
        // The IDE switched it on again through the new DDS before Spock connected: DDS replays its announcement.
        val laterWrite = trackingChanged("true")
        vm.replayOnListen = { stream -> if (stream == "Extension") vm.pushEvent("Extension", laterWrite) }

        val next = nextSession(owners)
        try {
            next.connect(pasted())
            eventually(message = "left as it is") { next.snapshot.rebuildFlagRestore == RebuildFlagRestore.LEFT_AS_IS }
            eventually(message = "not Spock's any more") { owners.ofProcess(SERIAL, PID).isEmpty() }
        } finally {
            vm.replayOnListen = {}
            next.close()
        }

        assertEquals(listOf("true"), writes())
        assertEquals("true", tracking)
    }

    @Test
    fun `a hot restart between the sessions took the flag along, and the record is forgotten`() {
        val owners = RebuildFlagOwners()
        leftOnByLostWindow(owners)
        val restarted = "isolates/5555"
        vm.addIsolate(restarted, listOf(FakeVmService.STRUCTURED_ERRORS, TRACK_REBUILDS, LOCATION_MAP))
        vm.viewIsolates = listOf(restarted)
        vm.isolates.remove(UI_ISOLATE)

        val next = nextSession(owners)
        try {
            next.connect(pasted())
            eventually(message = "the record forgotten") { owners.ofProcess(SERIAL, PID).isEmpty() }
            assertEquals(null, next.snapshot.rebuildFlagRestore, "nothing to say: Flutter reset it")
        } finally {
            next.close()
        }

        assertEquals(listOf("true"), writes())
    }

    @Test
    fun `what a lost window left on in another process is never written, and a new process drops it`() {
        val owners = RebuildFlagOwners()
        owners.record(RebuildFlagOwners.Owed(SERIAL, PID + 1, UI_ISOLATE, connectedAt))
        owners.record(RebuildFlagOwners.Owed("emulator-5556", PID + 1, UI_ISOLATE, connectedAt))
        tracking = "true"
        serveTracking()
        val next = nextSession(owners)
        try {
            // Nothing is owed this process, so nothing is queued: the connect is all there is.
            next.connect(pasted())
        } finally {
            next.close()
        }
        assertEquals(emptyList<String>(), writes())
        assertEquals(0, extensionCalls(TRACK_REBUILDS).size)

        owners.retainPids(SERIAL, setOf(PID.toLong()))

        assertEquals(emptyList<RebuildFlagOwners.Owed>(), owners.ofProcess(SERIAL, PID + 1))
        assertEquals(1, owners.ofProcess("emulator-5556", PID + 1).size, "another device's is kept")
    }

    @Test
    fun `a window Spock closed itself leaves nothing owed`() {
        val owners = RebuildFlagOwners()
        session.rebuildOwners = owners
        session.ownerSerial = SERIAL
        serveTracking()
        connected()

        recorder(during = { session.close() }).record(windowMs = 60_000, cancelled = { false })

        assertEquals(emptyList<RebuildFlagOwners.Owed>(), owners.ofProcess(SERIAL, PID))
    }

    @Test
    fun `a hot restart during the window stops the counts there, and the flag went with the old isolate`() {
        serveTracking()
        connected()
        val restarted = "isolates/5555"

        val recorded = recorder(
            during = {
                vm.addIsolate(restarted, listOf(FakeVmService.STRUCTURED_ERRORS, TRACK_REBUILDS, LOCATION_MAP))
                vm.viewIsolates = listOf(restarted)
                vm.isolates.remove(UI_ISOLATE)
                vm.pushEvent("Isolate", isolateEvent("IsolateExit", UI_ISOLATE, stamps.incrementAndGet()))
                vm.pushEvent("Isolate", isolateEvent("IsolateStart", restarted, stamps.incrementAndGet()))
                // The new isolate's first frames: not counted, the tracking Spock switched on is gone.
                pushRebuilt(count = 40, isolateId = restarted)
                awaitEventsHandled()
            },
        ).record(windowMs = 60_000, cancelled = { false }) as FlutterRebuildRecorder.Result.Recorded

        assertEquals(FlutterRebuildRecorder.Tracking.ISOLATE_GONE, recorded.tracking)
        assertEquals(listOf("true"), writes())
        assertEquals(2, recorded.report.frames)
        assertEquals(5L, recorded.report.top.single().rebuilds)
        assertTrue(recorded.windowMs < 60_000, "${recorded.windowMs}")
    }

    @Test
    fun `a second window on the same session is refused while the first runs, and the first still restores`() {
        serveTracking()
        connected()
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
        connected()
        var cancel = false
        val signal = CancellationSignal { cancel }

        val recorded = recorder(during = { cancel = true })
            .record(windowMs = 60_000, cancelled = signal) as FlutterRebuildRecorder.Result.Recorded

        assertTrue(recorded.windowMs < 60_000, "${recorded.windowMs}")
        assertEquals(listOf("true", "false"), writes())
    }

    private companion object {
        /** The whole tree, once: what a reassemble builds. */
        const val FORCED_BUILDS = 120
        const val SERIAL = "emulator-5554"

        /** The recorded `getVM`'s. */
        const val PID = 12345
    }
}
