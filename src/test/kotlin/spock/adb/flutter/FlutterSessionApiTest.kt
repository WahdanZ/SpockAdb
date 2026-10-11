package spock.adb.flutter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.HTTP_LOGGING
import spock.adb.flutter.vmservice.FakeVmService.Companion.STRUCTURED_ERRORS
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.NoUiIsolateException
import spock.adb.flutter.vmservice.ReadOnlyConnectionException
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServicePausedException

/** What callers read off a session: the snapshot, and calls that are scrubbed and, when read-only, only reads. */
class FlutterSessionApiTest : FlutterSessionFixture() {

    @Test
    fun `the snapshot carries the connection's facts while connected, and drops them after`() {
        session.connect(pasted())

        val connected = session.snapshot
        assertEquals(connectedAt, connected.connectedAtHostMs)
        assertEquals(12_345, connected.vmPid)
        assertEquals(1_727_776_800_000L, connected.vmStartTimeMs)
        assertEquals("android", connected.operatingSystem)

        session.close()

        val closed = session.snapshot
        assertNull(closed.connectedAtHostMs)
        assertNull(closed.vmPid)
        assertNull(closed.vmStartTimeMs)
        assertNull(closed.operatingSystem)
        assertNull(closed.connectionKind)
    }

    @Test
    fun `callUiExtension calls the UI isolate and scrubs the answer`() {
        vm.on("ext.flutter.example") {
            FakeVmService.Reply.Result(
                JsonObject().apply { addProperty("link", "http://127.0.0.1:1234/${FakeVmService.TOKEN}/") },
            )
        }
        session.connect(pasted())

        val result = session.callUiExtension("ext.flutter.example", mapOf("viewId" to "0"))

        val params = vm.requestsFor("ext.flutter.example").single().getAsJsonObject("params")
        assertEquals(UI_ISOLATE, params.get("isolateId").asString)
        assertEquals("0", params.get("viewId").asString)
        assertFalse(result.toString().contains(FakeVmService.TOKEN), result.toString())
    }

    @Test
    fun `callUiExtension is refused when not connected, paused, or with no UI isolate`() {
        assertThrows<VmServiceClosedException> { session.callUiExtension(STRUCTURED_ERRORS) }

        session.connect(pasted())
        vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, connectedAt + 1_000))
        eventually { session.state is SessionState.Paused }
        val before = extensionCalls(STRUCTURED_ERRORS).size
        val paused = assertThrows<VmServicePausedException> { session.callUiExtension(STRUCTURED_ERRORS) }
        assertEquals("PauseBreakpoint", paused.pauseKind)
        assertEquals(before, extensionCalls(STRUCTURED_ERRORS).size)
        session.close()

        vm.viewIsolates = null
        vm.isolates.clear()
        vm.addIsolate("isolates/0001", listOf("ext.dart.io.getVersion"))
        session.connect(pasted())
        assertThrows<NoUiIsolateException> { session.callUiExtension(STRUCTURED_ERRORS) }
    }

    @Test
    fun `on a read-only connection callUiExtension lets known reads through and refuses the rest`() {
        vm.dds = false
        session.connect(tracking(ddsLikely = false), allowDirect = true)

        assertThrows<ReadOnlyConnectionException> {
            session.callUiExtension(STRUCTURED_ERRORS, mapOf("enabled" to "false"))
        }
        assertThrows<ReadOnlyConnectionException> { session.callUiExtension("ext.flutter.exit") }
        assertThrows<ReadOnlyConnectionException> { session.callUiExtension("ext.flutter.reassemble") }
        assertThrows<ReadOnlyConnectionException> { session.callUiExtension("ext.dart.io.clearHttpProfile") }
        assertThrows<ReadOnlyConnectionException> {
            session.callUiExtension(HTTP_LOGGING, mapOf("enabled" to "true"))
        }
        assertEquals("true", session.callUiExtension(STRUCTURED_ERRORS).get("enabled").asString)
        assertEquals(false, session.callUiExtension(HTTP_LOGGING).get("enabled").asBoolean)
        assertTrue(vm.requestsFor("ext.flutter.exit").isEmpty())
        assertTrue(vm.requestsFor("ext.flutter.reassemble").isEmpty())
        assertEquals(emptyList<String>(), writes(STRUCTURED_ERRORS) + writes(HTTP_LOGGING))
    }

    @Test
    fun `callVm calls the VM and scrubs the answer`() {
        assertThrows<VmServiceClosedException> { session.callVm("_flutter.listViews") }
        vm.on("_flutter.getDisplayRefreshRate") {
            FakeVmService.Reply.Result(
                JsonObject().apply {
                    addProperty("fps", 60.0)
                    addProperty("note", "ws://127.0.0.1:1234/${FakeVmService.TOKEN}/ws")
                    add(
                        "nested",
                        JsonObject().apply {
                            add(
                                "list",
                                JsonArray().apply { add("see http://127.0.0.1:1234/${FakeVmService.TOKEN}/") },
                            )
                        },
                    )
                },
            )
        }
        session.connect(pasted())

        val rate = session.callVm("_flutter.getDisplayRefreshRate")

        assertEquals(60.0, rate.get("fps").asDouble)
        assertFalse(rate.toString().contains(FakeVmService.TOKEN), rate.toString())
        assertEquals("FlutterViewList", session.callVm("_flutter.listViews").get("type").asString)
    }

    @Test
    fun `callVm sends service extensions and stream history elsewhere`() {
        session.connect(pasted())

        assertThrows<IllegalArgumentException> { session.callVm("ext.flutter.exit") }
        assertThrows<IllegalArgumentException> { session.callVm("getStreamHistory") }

        assertTrue(vm.requestsFor("ext.flutter.exit").isEmpty())
        assertTrue(vm.requestsFor("getStreamHistory").isEmpty())
    }

    @Test
    fun `on a read-only connection callVm refuses known mutators and lets reads through`() {
        vm.dds = false
        session.connect(tracking(ddsLikely = false), allowDirect = true)

        val mutators = listOf(
            "resume", "kill", "pause", "reloadSources", "evaluate", "evaluateInFrame", "invoke", "setFlag",
            "setLibraryDebuggable", "addBreakpointWithScriptUri", "removeBreakpoint", "setIsolatePauseMode",
            "clearCpuSamples", "requestHeapSnapshot",
        )
        mutators.forEach { method ->
            assertThrows<ReadOnlyConnectionException>(method) { session.callVm(method) }
        }

        assertEquals("VM", session.callVm("getVM").get("type").asString)
        assertEquals("FlutterViewList", session.callVm("_flutter.listViews").get("type").asString)
        val isolate = JsonObject().apply { addProperty("isolateId", UI_ISOLATE) }
        assertEquals(UI_ISOLATE, session.callVm("getIsolate", isolate).get("id").asString)
        assertTrue(vm.requestsFor("resume").isEmpty() && vm.requestsFor("evaluate").isEmpty())
    }
}
