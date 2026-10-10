package spock.adb.timeline

import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterRebuildRecorder
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionChange
import spock.adb.flutter.FlutterSessionService
import spock.adb.flutter.HttpOwners
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.RebuildFlagOwners
import spock.adb.flutter.RebuildFlagRestore
import spock.adb.flutter.analysis.FlutterFixtures
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class FlutterTimelineRecorderTest {

    private val vm = FakeVmService()
    private val rows = CopyOnWriteArrayList<TimelineEvent>()

    @Volatile
    private var now = HOST_START
    private var ticking = 0
    private val profilesAsked = CopyOnWriteArrayList<Long?>()
    private var profile: JsonObject = JsonObject()

    private val reads = object : FlutterTimelineRecorder.Reads {
        override fun refreshRate(session: FlutterSession): Double = 60.0

        override fun httpProfile(session: FlutterSession, updatedSinceUs: Long?): JsonObject {
            profilesAsked += updatedSinceUs
            return profile
        }
    }

    private val recorder = FlutterTimelineRecorder(
        sink = { rows += it },
        ticker = {
            ticking++
            CompletableFuture<Unit>()
        },
        reads = reads,
        clock = { now },
    )
    private val session = FlutterSession(clock = { now })
    private val identity = AppIdentity(SERIAL, APP, 4242, IdentityCheck.DTD_PID_START)

    /** The device is 5 s ahead of the host, in Berlin. */
    private val deviceTime = DeviceTime(epochOffsetMs = 5_000, uncertaintyMs = 40, zone = ZoneOffset.ofHours(2))

    @AfterEach
    fun tearDown() {
        recorder.dispose()
        session.close()
        vm.close()
    }

    @Test
    fun `a session's start says which app, where, how and verified how`() {
        connect()

        val start = rows.single()
        assertEquals("Flutter session: $APP on $SERIAL — DDS, pid 4242 (verified by dtd+pid+start)", start.title)
        assertEquals(HOST_START, start.timeMs)
        assertEquals(TimelineCategory.APP_LIFECYCLE, start.category)
        assertEquals(1, ticking, "ticks while a session is live")
    }

    @Test
    fun `an error lands on the host's clock, with the device's own stamp kept`() {
        connect()
        val deviceMs = Instant.parse("2026-10-02T12:00:10Z").toEpochMilli()
        pushError(deviceMs)

        val row = awaitRow { it.category == TimelineCategory.FLUTTER_ERROR }

        assertEquals(deviceMs - 5_000, row.timeMs)
        assertEquals("10-02 14:00:10.000", row.deviceTime)
        assertEquals(SERIAL, row.deviceSerial)
    }

    @Test
    fun `rows wait for the device clock, then take it`() {
        connect(measured = false)
        pushError(DEVICE_START)
        eventually(message = "the error queued") { recorder.queued == 1 }
        recorder.tick()
        assertTrue(rows.none { it.category == TimelineCategory.FLUTTER_ERROR }, "held until the clock is measured")
        assertEquals(1, recorder.queued, "still queued, not dropped")

        session.deviceTime.set(deviceTime)

        assertEquals(DEVICE_START - 5_000, awaitRow { it.category == TimelineCategory.FLUTTER_ERROR }.timeMs)
    }

    @Test
    fun `debug frames make no rows`() {
        connect()
        repeat(FRAMES) { pushFrame(it, buildUs = 40_000) }
        awaitDelivered()
        now += FlutterTimelineRecorder.FRAME_WINDOW_MS
        recorder.tick()

        assertTrue(rows.none { it.category == TimelineCategory.FLUTTER_FRAME }, "$rows")
    }

    @Test
    fun `profile frames make one row per burst, not one per frame`() {
        vm.addIsolate(UI_ISOLATE, PROFILE_EXTENSIONS)
        connect()
        repeat(FRAMES) { pushFrame(it, buildUs = 40_000) }
        awaitDelivered()
        now += FlutterTimelineRecorder.FRAME_WINDOW_MS
        recorder.tick()

        val frames = rows.filter { it.category == TimelineCategory.FLUTTER_FRAME }
        assertEquals(1, frames.size, "$frames")
        assertTrue(frames.single().title.startsWith("$FRAMES of $FRAMES frames over"), frames.single().title)
    }

    @Test
    fun `Spock turning HTTP recording on is one row, and turning it back off another`() {
        connect()
        eventually(message = "HTTP recording on") { session.snapshot.httpRecording == HttpRecording.EnabledBySpock }
        recorder.tick()
        recorder.tick()

        val on = rows.filter { it.title.startsWith("Spock turned on HTTP recording") }
        assertEquals(1, on.size)
        assertEquals("Spock turned on HTTP recording for $APP (restored when Spock disconnects)", on.single().title)

        session.close()
        recorder.sessionChanged(
            FlutterSessionChange.Disconnected(session, identity, FlutterSessionService.DISCONNECTED_BY_SPOCK),
        )

        assertTrue(rows.any { it.title == "Flutter session ended: $APP — Spock disconnected" }, "$rows")
        assertTrue(rows.any { it.title == "Spock switched HTTP recording back off for $APP" }, "$rows")
    }

    @Test
    fun `logging an earlier session left on is adopted, and the row says so`() {
        val owners = HttpOwners().apply { record(HttpOwners.key(SERIAL, 12345, UI_ISOLATE)) }
        vm.httpLogging = true
        session.httpOwners = owners
        session.ownerSerial = SERIAL
        connect()
        eventually(message = "adopted") { session.snapshot.httpRecording == HttpRecording.AdoptedBySpock }

        recorder.tick()

        assertTrue(
            rows.any {
                it.title == "Spock's HTTP recording for $APP from the earlier session is still on; " +
                    "Spock will switch it off when it disconnects"
            },
            "$rows",
        )
        assertTrue(rows.none { it.title.startsWith("Spock turned on HTTP recording") })
    }

    @Test
    fun `rebuild tracking an earlier recording left on is switched off, and the row says so`() {
        var tracking = "true"
        vm.on(FlutterRebuildRecorder.TRACK_REBUILDS) { params ->
            params.get("enabled")?.let { tracking = it.asString }
            FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", tracking) })
        }
        session.rebuildOwners = RebuildFlagOwners().apply {
            record(RebuildFlagOwners.Owed(SERIAL, 12345, UI_ISOLATE, lastAnnouncedAt = DEVICE_START))
        }
        session.ownerSerial = SERIAL
        connect()
        eventually(message = "switched off") { session.snapshot.rebuildFlagRestore == RebuildFlagRestore.SWITCHED_OFF }

        recorder.tick()
        recorder.tick()

        val restored = rows.filter { it.title.startsWith("Spock switched off the rebuild tracking") }
        assertEquals(1, restored.size, "once: $rows")
        assertEquals(
            "Spock switched off the rebuild tracking its earlier recording left on for $APP when that session's " +
                "connection was lost",
            restored.single().title,
        )
        assertEquals("false", tracking)
    }

    @Test
    fun `a restore that did not happen is not reported as done`() {
        connect()
        eventually(message = "HTTP recording on") { session.snapshot.httpRecording == HttpRecording.EnabledBySpock }
        recorder.tick()

        recorder.sessionChanged(
            FlutterSessionChange.Disconnected(session, identity, FlutterSessionService.DISCONNECTED_BY_SPOCK),
        )

        val row = rows.single { it.category == TimelineCategory.HTTP && "back off" in it.title }
        assertEquals(TimelineSeverity.WARNING, row.severity)
        assertTrue(row.title.startsWith("Spock could not switch HTTP recording back off"), row.title)
    }

    @Test
    fun `a refresh rate that times out does not stop the ticks`() {
        vm.addIsolate(UI_ISOLATE, PROFILE_EXTENSIONS)
        val timingOut = FlutterTimelineRecorder(
            sink = { rows += it },
            ticker = { CompletableFuture<Unit>() },
            reads = object : FlutterTimelineRecorder.Reads {
                override fun refreshRate(session: FlutterSession): Double =
                    throw VmServiceTimeoutException("_flutter.listViews", 2_000)

                override fun httpProfile(session: FlutterSession, updatedSinceUs: Long?) = JsonObject()
            },
            clock = { now },
        )
        timingOut.sessionCreated(session)
        session.connect(PastedUriDiscovery(vm.uri).discover().single())
        session.deviceTime.set(deviceTime)
        timingOut.sessionChanged(FlutterSessionChange.Connected(session, identity))
        eventually(message = "a profile build") { session.buildMode == FlutterBuild.PROFILE }

        timingOut.tick()
        pushError(DEVICE_START)
        eventually(message = "the error row") {
            timingOut.tick()
            rows.any { it.category == TimelineCategory.FLUTTER_ERROR }
        }
        timingOut.dispose()
    }

    @Test
    fun `a new session on the same process writes no row twice`() {
        profile = FlutterFixtures.json("httpProfile.json")
        connect()
        pushError(DEVICE_START)
        awaitRow { it.category == TimelineCategory.FLUTTER_ERROR }
        now += FlutterTimelineRecorder.HTTP_POLL_MS
        recorder.tick()
        // Errors and failed requests; Spock turning recording on again for the new session is news.
        fun failures() =
            rows.filter { it.category == TimelineCategory.FLUTTER_ERROR || it.severity == TimelineSeverity.ERROR }
        val before = failures()
        assertTrue(before.any { it.category == TimelineCategory.HTTP }, "$rows")

        // The same app's process, a second session: DDS replays the error, the profile still holds the request.
        val second = FlutterSession(clock = { now })
        vm.replayOnListen = { stream -> if (stream == "Extension") pushError(DEVICE_START) }
        // As the service does: the old session closes before the new one connects.
        session.close()
        recorder.sessionCreated(second)
        second.connect(PastedUriDiscovery(vm.uri).discover().single())
        second.deviceTime.set(deviceTime)
        recorder.sessionChanged(FlutterSessionChange.Replaced(session, identity, second, identity))
        awaitDelivered(second)
        now += FlutterTimelineRecorder.HTTP_POLL_MS
        recorder.tick()
        second.close()

        assertEquals(before, failures())
    }

    @Test
    fun `history replayed during connect is recorded once, marked as such`() {
        vm.replayOnListen = { stream -> if (stream == "Extension") pushError(DEVICE_START) }
        connect()

        val row = awaitRow { it.category == TimelineCategory.FLUTTER_ERROR }
        awaitDelivered()

        assertEquals(1, rows.count { it.category == TimelineCategory.FLUTTER_ERROR })
        assertTrue(row.detail.startsWith("Replayed on connect"), row.detail)
    }

    @Test
    fun `with no device clock the rows say whose clock they are on`() {
        connect(measured = false)
        session.deviceTime.set(null)
        pushError(DEVICE_START)

        awaitRow { it.category == TimelineCategory.FLUTTER_ERROR }

        assertTrue(rows.any { it.title.startsWith("Device clock not measured") }, "$rows")
    }

    @Test
    fun `while device events are not recorded, nothing arrives and the app is not asked`() {
        var active = true
        val paused = FlutterTimelineRecorder(
            sink = { rows += it },
            ticker = { CompletableFuture<Unit>() },
            reads = reads,
            clock = { now },
            active = { active },
        )
        paused.sessionCreated(session)
        session.connect(PastedUriDiscovery(vm.uri).discover().single())
        session.deviceTime.set(deviceTime)
        paused.sessionChanged(FlutterSessionChange.Connected(session, identity))
        active = false
        pushError(DEVICE_START)
        eventually(message = "the error queued") { paused.queued == 1 }
        now += FlutterTimelineRecorder.HTTP_POLL_MS

        paused.tick()

        assertEquals(0, paused.queued, "dropped, not kept for later")
        assertTrue(rows.none { it.category == TimelineCategory.FLUTTER_ERROR })
        assertEquals(emptyList<Long?>(), profilesAsked.toList())
        paused.dispose()
    }

    @Test
    fun `a lost connection ends the session with why, as a warning`() {
        connect()
        recorder.sessionChanged(FlutterSessionChange.Disconnected(session, identity, "connection closed (1006)"))

        val end = rows.last()
        assertEquals(TimelineSeverity.WARNING, end.severity)
        assertTrue(end.title.startsWith("Flutter session ended: $APP — the connection was lost"), end.title)
    }

    @Test
    fun `the engine destroyed by Back at the root says so, while the session stays`() {
        connect()
        eventually(message = "a UI isolate") { session.snapshot.uiIsolateId == UI_ISOLATE }
        recorder.tick()
        vm.isolates.remove(UI_ISOLATE)
        vm.viewIsolates = emptyList()
        vm.pushEvent("Isolate", isolateEvent("IsolateExit", UI_ISOLATE, DEVICE_START + 1))
        eventually(message = "no UI isolate") { session.snapshot.uiIsolateId == null }
        recorder.tick()
        now += FlutterTimelineRecorder.ISOLATE_GRACE_MS
        recorder.tick()

        val row = rows.single { it.title.contains("UI isolate exited") }
        assertEquals(TimelineSeverity.WARNING, row.severity)
        assertTrue(row.title.contains("Back at the root activity"), row.title)
    }

    @Test
    fun `failed requests are polled while recording, one row per request`() {
        profile = FlutterFixtures.json("httpProfile.json")
        connect()
        eventually(message = "HTTP recording on") { session.snapshot.httpRecording == HttpRecording.EnabledBySpock }
        now += FlutterTimelineRecorder.HTTP_POLL_MS
        recorder.tick()
        val first = rows.filter { it.category == TimelineCategory.HTTP && it.title.contains("/status/") }
        now += FlutterTimelineRecorder.HTTP_POLL_MS
        recorder.tick()

        assertTrue(first.isNotEmpty(), "$rows")
        assertEquals(first, rows.filter { it.category == TimelineCategory.HTTP && it.title.contains("/status/") })
        assertEquals(listOf(null, profile["timestamp"].asLong), profilesAsked.toList(), "paged by updatedSince")
    }

    @Test
    fun `nothing is polled when HTTP is not recorded`() {
        session.close()
        val noHttp = FlutterSession(clock = { now })
        recorder.sessionCreated(noHttp)
        noHttp.connect(PastedUriDiscovery(vm.uri).discover().single(), recordHttp = false)
        noHttp.deviceTime.set(deviceTime)
        recorder.sessionChanged(FlutterSessionChange.Connected(noHttp, identity))
        now += FlutterTimelineRecorder.HTTP_POLL_MS
        recorder.tick()
        noHttp.close()

        assertEquals(emptyList<Long?>(), profilesAsked.toList())
        assertTrue(rows.none { it.category == TimelineCategory.HTTP })
    }

    // ---------------------------------------------------------------- helpers

    private fun connect(measured: Boolean = true) {
        recorder.sessionCreated(session)
        session.connect(PastedUriDiscovery(vm.uri).discover().single())
        if (measured) session.deviceTime.set(deviceTime)
        recorder.sessionChanged(FlutterSessionChange.Connected(session, identity))
    }

    private fun pushError(deviceMs: Long) = vm.pushEvent(
        "Extension",
        isolateEvent("Extension", UI_ISOLATE, deviceMs) {
            addProperty("extensionKind", "Flutter.Error")
            add("extensionData", FlutterFixtures.events("Flutter.Error").first().data)
        },
    )

    private fun pushFrame(number: Int, buildUs: Long) = vm.pushEvent(
        "Extension",
        isolateEvent("Extension", UI_ISOLATE, DEVICE_START + number * FRAME_US / 1_000) {
            addProperty("extensionKind", "Flutter.Frame")
            add(
                "extensionData",
                JsonObject().apply {
                    addProperty("number", number)
                    addProperty("startTime", START_US + number * FRAME_US)
                    addProperty("elapsed", buildUs + 1_000)
                    addProperty("build", buildUs)
                    addProperty("raster", 2_000)
                    addProperty("vsyncOverhead", 100)
                },
            )
        },
    )

    /** Everything pushed so far has reached the recorder: a marker navigation event arrived. */
    private fun awaitDelivered(on: FlutterSession = session) {
        val marker = "/marker-${System.nanoTime()}"
        val placementWas = on.deviceTime.current
        vm.pushEvent(
            "Extension",
            isolateEvent("Extension", UI_ISOLATE, DEVICE_START) {
                addProperty("extensionKind", "Flutter.Navigation")
                add(
                    "extensionData",
                    JsonObject().apply {
                        val settings = JsonObject().apply { addProperty("name", marker) }
                        add("route", JsonObject().apply { add("settings", settings) })
                    },
                )
            },
        )
        if (placementWas == null) {
            // Rows wait for the clock: the marker cannot be seen as a row yet, so give it time to arrive.
            Thread.sleep(DELIVERY_MS)
            return
        }
        awaitRow { it.title.contains(marker) }
    }

    private fun awaitRow(predicate: (TimelineEvent) -> Boolean): TimelineEvent {
        eventually(message = "a timeline row") {
            recorder.tick()
            rows.any(predicate)
        }
        return rows.first(predicate)
    }

    private companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "spock.adb.spock_flutter_sample"
        const val HOST_START = 1_790_000_000_000L
        val DEVICE_START: Long = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
        const val START_US = 9_000_000_000L
        const val FRAME_US = 16_667L
        const val FRAMES = 30
        const val DELIVERY_MS = 300L

        /** A profile build's UI isolate: Flutter's extensions without the inspector's, and dart:io's. */
        val PROFILE_EXTENSIONS = listOf(
            "ext.flutter.reassemble",
            "ext.flutter.exit",
            "ext.flutter.debugPaint",
            "ext.dart.io.httpEnableTimelineLogging",
            "ext.dart.io.getHttpProfile",
        )
    }
}
