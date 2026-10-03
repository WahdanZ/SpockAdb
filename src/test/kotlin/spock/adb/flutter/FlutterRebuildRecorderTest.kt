package spock.adb.flutter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterRebuildRecorder.Companion.LOCATION_MAP
import spock.adb.flutter.FlutterRebuildRecorder.Companion.TRACK_REBUILDS
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.fixture

class FlutterRebuildRecorderTest : FlutterSessionFixture() {

    private var tracking = false

    private fun connect() {
        val rpcs = fixture("getIsolate-ui-debug.json").getAsJsonArray("extensionRPCs").map { it.asString } +
            listOf(TRACK_REBUILDS, LOCATION_MAP)
        vm.addIsolate(UI_ISOLATE, rpcs)
        vm.on(TRACK_REBUILDS) { params ->
            params.get("enabled")?.let { tracking = it.asString == "true" }
            FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", tracking.toString()) })
        }
        vm.on(LOCATION_MAP) {
            FakeVmService.Reply.Result(
                JsonObject().apply {
                    add(
                        "file:///project/lib/home.dart",
                        JsonObject().apply {
                            add("ids", JsonArray().apply { add(7) })
                            add("lines", JsonArray().apply { add(42) })
                            add("columns", JsonArray().apply { add(9) })
                            add("names", JsonArray().apply { add("HomeCard") })
                        },
                    )
                },
            )
        }
        session.connect(pasted())
    }

    @Test
    fun `a recording switches rebuild tracking on and off only when Spock owns it`() {
        connect()
        val recorder = FlutterRebuildRecorder { pushRebuild() }

        val report = recorder.record(session, durationMs = 1_000)

        assertEquals(listOf("true", "false"), writes(TRACK_REBUILDS, UI_ISOLATE))
        assertEquals(1, report.frames)
        assertEquals(3, report.top.single().rebuilds)
        assertTrue(report.top.single().label.contains("HomeCard at lib/home.dart:42:9"))
    }

    @Test
    fun `tracking already enabled is read and left enabled`() {
        tracking = true
        connect()
        val recorder = FlutterRebuildRecorder { pushRebuild() }

        recorder.record(session, durationMs = 1_000)

        assertEquals(emptyList<String>(), writes(TRACK_REBUILDS, UI_ISOLATE))
        assertTrue(tracking)
    }

    @Test
    fun `a direct read-only session refuses rebuild recording`() {
        vm.dds = false
        val rpcs = fixture("getIsolate-ui-debug.json").getAsJsonArray("extensionRPCs").map { it.asString } +
            listOf(TRACK_REBUILDS, LOCATION_MAP)
        vm.addIsolate(UI_ISOLATE, rpcs)
        session.connect(pasted(), allowDirect = true)

        val failure = assertThrows<IllegalArgumentException> {
            FlutterRebuildRecorder().record(session, durationMs = 1_000)
        }

        assertTrue(failure.message.orEmpty().contains("DDS"))
        assertEquals(0, extensionCalls(TRACK_REBUILDS).size)
    }

    private fun pushRebuild() {
        vm.pushEvent(
            "Extension",
            FakeVmService.isolateEvent("Extension", UI_ISOLATE, connectedAt + 1_000) {
                addProperty("extensionKind", "Flutter.RebuiltWidgets")
                add(
                    "extensionData",
                    JsonObject().apply {
                        add("events", JsonArray().apply { add(7); add(3) })
                        addProperty("startTime", 1_000_000)
                    },
                )
            },
        )
        awaitEventsHandled()
    }
}
