package spock.adb.timeline

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionChange
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.analysis.FlutterFixtures
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.mcp.tools.GetDebugTimelineTool
import spock.adb.mcp.tools.ToolContent
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

/**
 * #182 F-12: what a live Flutter session records reaches `android_get_debug_timeline` — the
 * recorder's rows in the timeline the tool reads, on the path [DebugTimelineService] wires — and the
 * tool's description no longer says those categories stay empty.
 */
class FlutterTimelineToolTest {

    private val vm = FakeVmService()
    private val timeline = DebugTimeline()
    private val tool = GetDebugTimelineTool()

    @Volatile
    private var now = HOST_START

    private val recorder = FlutterTimelineRecorder(
        sink = timeline::record,
        ticker = { CompletableFuture<Unit>() },
        reads = object : FlutterTimelineRecorder.Reads {
            override fun refreshRate(session: FlutterSession): Double = 60.0

            override fun httpProfile(session: FlutterSession, updatedSinceUs: Long?): JsonObject =
                FlutterFixtures.json("httpProfile.json")
        },
        clock = { now },
    )
    private val session = FlutterSession(clock = { now })

    @AfterEach
    fun tearDown() {
        recorder.dispose()
        session.close()
        vm.close()
    }

    @Test
    fun `a Flutter error, a route and a failed request come out of the timeline tool`() {
        recorder.sessionCreated(session)
        session.connect(PastedUriDiscovery(vm.uri).discover().single())
        session.deviceTime.set(DeviceTime(epochOffsetMs = 0, uncertaintyMs = 40, zone = ZoneOffset.UTC))
        recorder.sessionChanged(
            FlutterSessionChange.Connected(session, AppIdentity(SERIAL, APP, 4242, IdentityCheck.DTD_PID_START)),
        )
        eventually(message = "HTTP recording on") { session.snapshot.httpRecording == HttpRecording.EnabledBySpock }
        vm.pushEvent("Extension", extension("Flutter.Error", FlutterFixtures.events("Flutter.Error").first().data))
        vm.pushEvent("Extension", extension("Flutter.Navigation", navigation("/item/42")))
        now += FlutterTimelineRecorder.HTTP_POLL_MS
        eventually(message = "the three rows") {
            recorder.tick()
            listOf(TimelineCategory.FLUTTER_ERROR, TimelineCategory.NAVIGATION, TimelineCategory.HTTP).all { category ->
                timeline.snapshot().any { it.category == category }
            }
        }

        val arguments = JsonObject().apply {
            add("categories", JsonArray().apply { listOf("flutter_error", "navigation", "http").forEach(::add) })
            addProperty("sinceSeconds", 86_400)
        }
        val result = tool.answer(timeline, "spock.adb.spock_flutter_sample on emulator-5554", arguments, now)
        val text = (result.content.single() as ToolContent.Text).text

        assertFalse(result.isError, text)
        assertTrue(text.contains("Flutter error"), text)
        assertTrue(text.contains("Navigator: /item/42"), text)
        assertTrue(text.contains("/status/"), text)
    }

    @Test
    fun `the description says where Flutter rows come from, not that they are always empty`() {
        assertFalse(tool.description.contains("does not open yet"), tool.description)
        assertTrue(tool.description.contains("flutter_app_status"), tool.description)
    }

    private fun extension(kind: String, data: JsonObject) = isolateEvent("Extension", UI_ISOLATE, DEVICE_MS) {
        addProperty("extensionKind", kind)
        add("extensionData", data)
    }

    private fun navigation(name: String) = JsonObject().apply {
        val settings = JsonObject().apply { addProperty("name", name) }
        add("route", JsonObject().apply { add("settings", settings) })
    }

    private companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "spock.adb.spock_flutter_sample"
        val DEVICE_MS: Long = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
        val HOST_START: Long = DEVICE_MS + 1_000
    }
}
