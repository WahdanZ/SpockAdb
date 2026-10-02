package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.STRUCTURED_ERRORS
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.NoUiIsolateException
import spock.adb.flutter.vmservice.ReadOnlyConnectionException
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServicePausedException
import java.util.concurrent.CopyOnWriteArrayList

/** What callers read off a session: the snapshot, a late listener's catch-up, and scrubbed calls. */
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
    fun `a listener added late with replayState hears the current state first, then changes, none twice`() {
        session.connect(pasted())
        val states = CopyOnWriteArrayList<SessionState>()
        val late = object : FlutterSessionListener {
            override fun onEvent(event: FlutterEvent) = Unit
            override fun onStateChanged(state: SessionState) {
                states += state
            }
        }

        session.addListener(late, replayState = true)
        assertEquals(listOf<SessionState>(SessionState.Connected(UI_ISOLATE)), states.toList())

        session.close()
        assertEquals(
            listOf(SessionState.Connected(UI_ISOLATE), SessionState.Disconnected(FlutterSession.CLOSED_BY_SPOCK)),
            states.toList(),
        )
    }

    @Test
    fun `a listener added late without replayState hears only later changes`() {
        session.connect(pasted())
        val states = CopyOnWriteArrayList<SessionState>()
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    states += state
                }
            },
        )

        session.close()

        assertEquals(listOf<SessionState>(SessionState.Disconnected(FlutterSession.CLOSED_BY_SPOCK)), states.toList())
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
    fun `callUiExtension refuses a write on a read-only connection, and reads`() {
        vm.dds = false
        session.connect(tracking(ddsLikely = false), allowDirect = true)

        assertThrows<ReadOnlyConnectionException> {
            session.callUiExtension(STRUCTURED_ERRORS, mapOf("enabled" to "false"))
        }
        assertEquals("true", session.callUiExtension(STRUCTURED_ERRORS).get("enabled").asString)
        assertEquals(emptyList<String>(), writes(STRUCTURED_ERRORS))
    }

    @Test
    fun `callVm calls the VM and scrubs the answer`() {
        assertThrows<VmServiceClosedException> { session.callVm("_flutter.listViews") }
        vm.on("_flutter.getDisplayRefreshRate") {
            FakeVmService.Reply.Result(
                JsonObject().apply {
                    addProperty("fps", 60.0)
                    addProperty("note", "ws://127.0.0.1:1234/${FakeVmService.TOKEN}/ws")
                },
            )
        }
        session.connect(pasted())

        val rate = session.callVm("_flutter.getDisplayRefreshRate")

        assertEquals(60.0, rate.get("fps").asDouble)
        assertFalse(rate.toString().contains(FakeVmService.TOKEN), rate.toString())
        assertEquals("FlutterViewList", session.callVm("_flutter.listViews").get("type").asString)
    }
}
