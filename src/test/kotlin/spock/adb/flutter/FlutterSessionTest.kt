package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.HTTP_LOGGING
import spock.adb.flutter.vmservice.FakeVmService.Companion.STRUCTURED_ERRORS
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.fixture
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class FlutterSessionTest {

    private val vm = FakeVmService()
    private val connectedAt = 1_727_776_801_500L
    private val session = FlutterSession(clock = { connectedAt })
    private val events = CopyOnWriteArrayList<FlutterEvent>()

    init {
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) {
                    events += event
                }
            },
        )
    }

    @AfterEach
    fun tearDown() {
        session.close()
        vm.close()
    }

    /** A candidate that counts its releases, so the `adb forward` clean-up can be checked. */
    private class TrackingCandidate(private val uri: VmServiceUri, ddsLikely: Boolean) :
        VmServiceCandidate(VmServiceSource.LOGCAT, ddsLikely) {
        @Volatile
        var released = 0
        override val description: String get() = uri.redacted()
        override fun open(): VmServiceUri = uri
        override fun release() {
            released++
        }
    }

    private fun pasted() = PastedUriDiscovery(vm.uri).discover().single()

    private fun tracking(ddsLikely: Boolean = true) = TrackingCandidate(VmServiceUri.parse(vm.uri), ddsLikely)

    private fun extensionCalls(method: String, isolateId: String? = null) =
        vm.requestsFor(method).map { it.getAsJsonObject("params") }
            .filter { isolateId == null || it.get("isolateId").asString == isolateId }

    private fun writes(method: String, isolateId: String? = null) =
        extensionCalls(method, isolateId).filter { it.has("enabled") }.map { it.get("enabled").asString }

    private fun stateChanged(value: String, timestamp: Long, isolateId: String = UI_ISOLATE) =
        isolateEvent("Extension", isolateId, timestamp) {
            addProperty("extensionKind", "Flutter.ServiceExtensionStateChanged")
            add(
                "extensionData",
                JsonObject().apply {
                    addProperty("extension", STRUCTURED_ERRORS)
                    addProperty("value", value)
                },
            )
        }

    @Test
    fun `connect picks the isolate behind the Flutter view and reads the VM`() {
        session.connect(pasted())

        assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
        assertEquals(UI_ISOLATE, session.uiIsolateId)
        assertTrue(session.selection is IsolateSelection.Selected)
        assertEquals(connectedAt, session.connectedAt)
        assertEquals("VM", session.vm?.get("type")?.asString)
        assertEquals(FlutterBuild.DEBUG, session.buildMode)
        assertFalse(session.readOnly)
        assertEquals(
            FlutterSession.DEFAULT_STREAMS,
            vm.requestsFor("streamListen").map { it.getAsJsonObject("params").get("streamId").asString },
        )
    }

    @Test
    fun `structuredErrors is read on connect and never set`() {
        session.connect(pasted())

        assertEquals(true, session.structuredErrorsEnabled)
        assertEquals(1, extensionCalls(STRUCTURED_ERRORS).size)
        assertEquals(emptyList<String>(), writes(STRUCTURED_ERRORS))
    }

    @Test
    fun `structuredErrors follows a live state change, not a replayed one`() {
        vm.afterStreamListen = { stream ->
            if (stream == "Extension") vm.pushEvent("Extension", stateChanged("false", connectedAt - 60_000))
        }
        session.connect(pasted())
        eventually(message = "the replayed event") { events.any { it.history } }
        assertEquals(true, session.structuredErrorsEnabled)

        vm.pushEvent("Extension", stateChanged("false", connectedAt + 1_000))
        eventually(message = "the live change") { session.structuredErrorsEnabled == false }
        assertEquals(emptyList<String>(), writes(STRUCTURED_ERRORS))
    }

    @Test
    fun `without listViews the isolate with the inspector is chosen over others`() {
        vm.viewIsolates = null
        vm.addIsolate("isolates/0001", listOf("ext.dart.io.getVersion"))
        vm.addIsolate("isolates/9999", listOf("ext.dart.io.getVersion", "ext.flutter.exit"))

        session.connect(pasted())

        assertEquals(UI_ISOLATE, session.uiIsolateId)
    }

    @Test
    fun `without the inspector any isolate with Flutter extensions is chosen, and it reads as a profile build`() {
        vm.viewIsolates = null
        vm.isolates.clear()
        vm.addIsolate("isolates/0001", listOf("ext.dart.io.getVersion"))
        vm.addIsolate("isolates/4444", listOf("ext.flutter.exit", "ext.flutter.timeDilation", HTTP_LOGGING))

        session.connect(pasted())

        assertEquals("isolates/4444", session.uiIsolateId)
        assertEquals(FlutterBuild.PROFILE, session.buildMode)
        assertNull(session.structuredErrorsEnabled)
        assertEquals(0, extensionCalls(STRUCTURED_ERRORS).size)
    }

    @Test
    fun `no Flutter isolate at all is reported, not guessed`() {
        vm.viewIsolates = null
        vm.isolates.clear()
        vm.addIsolate("isolates/0001", listOf("ext.dart.io.getVersion"))

        session.connect(pasted())

        assertTrue(session.selection is IsolateSelection.NoFlutterIsolate)
        assertEquals(SessionState.Connected(null), session.state)
        assertNull(session.buildMode)
    }

    @Test
    fun `several Flutter views leave the choice to the caller`() {
        vm.viewIsolates = listOf(UI_ISOLATE, "isolates/3333")
        val rpcs = fixture("getIsolate-ui-debug.json").getAsJsonArray("extensionRPCs").map { it.asString }
        vm.addIsolate("isolates/3333", rpcs)

        session.connect(pasted())

        val selection = session.selection as IsolateSelection.Ambiguous
        assertEquals(listOf(UI_ISOLATE, "isolates/3333"), selection.candidates.map { it.id })
        assertNull(session.uiIsolateId)
        assertEquals(0, extensionCalls(HTTP_LOGGING).size)

        val chosen = session.selectIsolate("isolates/3333")

        assertTrue(chosen is IsolateSelection.Selected)
        assertEquals("isolates/3333", session.uiIsolateId)
        assertEquals(listOf("true"), writes(HTTP_LOGGING, "isolates/3333"))
    }

    @Test
    fun `an isolate paused in the debugger is reported instead of called`() {
        vm.addIsolate(UI_ISOLATE, listOf(STRUCTURED_ERRORS, HTTP_LOGGING), pauseKind = "PauseBreakpoint")

        session.connect(pasted())

        val selection = session.selection as IsolateSelection.Paused
        assertTrue(selection.message.contains("paused in the debugger"), selection.message)
        assertEquals(SessionState.Paused(UI_ISOLATE, "PauseBreakpoint"), session.state)
        assertEquals(0, extensionCalls(STRUCTURED_ERRORS).size + extensionCalls(HTTP_LOGGING).size)

        vm.addIsolate(UI_ISOLATE, listOf(STRUCTURED_ERRORS, HTTP_LOGGING))
        vm.pushEvent("Debug", isolateEvent("Resume", UI_ISOLATE, connectedAt + 1_000))

        eventually(message = "the resumed isolate") { session.state == SessionState.Connected(UI_ISOLATE) }
        assertEquals(true, session.structuredErrorsEnabled)
    }

    @Test
    fun `pausing and resuming the UI isolate is followed`() {
        session.connect(pasted())

        vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, connectedAt + 1_000))
        eventually { session.state == SessionState.Paused(UI_ISOLATE, "PauseBreakpoint") }

        vm.pushEvent("Debug", isolateEvent("Resume", UI_ISOLATE, connectedAt + 2_000))
        eventually { session.state == SessionState.Connected(UI_ISOLATE) }
    }

    @Test
    fun `a hot restart moves the session to the new isolate as its extensions arrive`() {
        session.connect(pasted())
        val restarted = "isolates/5555"
        vm.httpLogging = false
        vm.addIsolate(restarted, listOf(HTTP_LOGGING))
        vm.viewIsolates = listOf(restarted)
        vm.isolates.remove(UI_ISOLATE)

        vm.pushEvent("Isolate", isolateEvent("IsolateExit", UI_ISOLATE, connectedAt + 1_000))
        vm.pushEvent("Isolate", isolateEvent("IsolateStart", restarted, connectedAt + 1_001))

        eventually(message = "the new isolate") { session.uiIsolateId == restarted }
        eventually(message = "logging on the new isolate") { writes(HTTP_LOGGING, restarted) == listOf("true") }
        assertNull(session.structuredErrorsEnabled)

        vm.pushEvent(
            "Isolate",
            isolateEvent("ServiceExtensionAdded", restarted, connectedAt + 1_002) {
                addProperty("extensionRPC", STRUCTURED_ERRORS)
            },
        )
        eventually(message = "structuredErrors read on the new isolate") { session.structuredErrorsEnabled == true }
        assertEquals(1, extensionCalls(STRUCTURED_ERRORS, restarted).size)
        assertEquals(emptyList<String>(), writes(STRUCTURED_ERRORS))
    }

    @Test
    fun `a hot restart that announces the new isolate before the old one exits is followed too`() {
        session.connect(pasted())
        val restarted = "isolates/6666"
        vm.addIsolate(restarted, listOf(STRUCTURED_ERRORS, HTTP_LOGGING))
        vm.viewIsolates = listOf(restarted)

        vm.pushEvent("Isolate", isolateEvent("IsolateStart", restarted, connectedAt + 1_000))
        vm.isolates.remove(UI_ISOLATE)
        vm.pushEvent("Isolate", isolateEvent("IsolateExit", UI_ISOLATE, connectedAt + 1_001))

        eventually(message = "the new isolate") { session.uiIsolateId == restarted }
    }

    @Test
    fun `a background isolate starting and exiting leaves the selection alone`() {
        session.connect(pasted())
        val listViewsCalls = vm.requestsFor("_flutter.listViews").size
        vm.addIsolate("isolates/7777", listOf("ext.dart.io.getVersion"))

        vm.pushEvent("Isolate", isolateEvent("IsolateStart", "isolates/7777", connectedAt + 1_000))
        vm.pushEvent("Isolate", isolateEvent("IsolateExit", "isolates/7777", connectedAt + 1_001))
        // Events are handled in order, so once this one is seen the two before it were handled.
        vm.pushEvent("Extension", fixture("event-frame.json").apply { addProperty("timestamp", connectedAt + 1_002) })
        eventually { events.any { it.extensionKind == "Flutter.Frame" } }

        assertEquals(listViewsCalls, vm.requestsFor("_flutter.listViews").size)
        assertEquals(UI_ISOLATE, session.uiIsolateId)
    }

    @Test
    fun `HTTP logging that was off is switched on, and off again on close`() {
        session.connect(pasted())

        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertTrue(vm.httpLogging)

        session.close()

        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `HTTP logging that was already on is left alone, on connect and on close`() {
        vm.httpLogging = true

        session.connect(pasted())
        session.close()

        assertEquals(1, extensionCalls(HTTP_LOGGING).size)
        assertEquals(emptyList<String>(), writes(HTTP_LOGGING))
        assertTrue(vm.httpLogging)
    }

    @Test
    fun `a direct connection reads but writes nothing`() {
        val candidate = tracking(ddsLikely = false)

        session.connect(candidate)
        session.close()

        assertTrue(candidate.direct)
        assertEquals(emptyList<String>(), writes(HTTP_LOGGING) + writes(STRUCTURED_ERRORS))
        assertEquals(true, session.structuredErrorsEnabled)
        assertEquals(1, candidate.released)
    }

    @Test
    fun `replayed events are marked as history and repeats are dropped`() {
        val older = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt - 2_000) }
        val old = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt - 1_000) }
        val live = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt + 1_000) }
        vm.afterStreamListen = { stream ->
            if (stream == "Extension") listOf(older, old, old, live).forEach { vm.pushEvent("Extension", it) }
            if (stream == "Logging") {
                listOf("first", "second").forEach { message ->
                    vm.pushEvent(
                        "Logging",
                        isolateEvent("Logging", UI_ISOLATE, connectedAt - 10) {
                            add("logRecord", JsonObject().apply { addProperty("message", message) })
                        },
                    )
                }
            }
        }

        session.connect(pasted())

        eventually(message = "five events") { events.size >= 5 }
        Thread.sleep(200)
        val frames = events.filter { it.extensionKind == "Flutter.Frame" }
        assertEquals(listOf(connectedAt - 2_000, connectedAt - 1_000, connectedAt + 1_000), frames.map { it.timestamp })
        assertEquals(listOf(true, true, false), frames.map { it.history })
        // Two log records in one millisecond are two records, not a repeat.
        assertEquals(2, events.count { it.kind == "Logging" })
    }

    @Test
    fun `a lost connection is a disconnected state with the reason, and the forward is released`() {
        val candidate = tracking()
        val states = CopyOnWriteArrayList<SessionState>()
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    states += state
                }
            },
        )
        session.connect(candidate)

        vm.server.drop()

        eventually { session.state is SessionState.Disconnected }
        val reason = (session.state as SessionState.Disconnected).reason
        assertFalse(reason == "closed by Spock", reason)
        assertEquals(1, candidate.released)
        assertNull(session.client)
        assertTrue(states.last() is SessionState.Disconnected)
    }

    @Test
    fun `after a lost connection, a reconnect still switches off the logging Spock switched on`() {
        session.connect(pasted())
        vm.server.drop()
        eventually { session.state is SessionState.Disconnected }

        session.connect(pasted())
        session.close()

        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `close disconnects, closes the client and releases the candidate once`() {
        val candidate = tracking()
        val client = session.connect(candidate)

        session.close()
        session.close()

        assertEquals(SessionState.Disconnected("closed by Spock"), session.state)
        assertFalse(client.isOpen)
        assertEquals(1, candidate.released)
        assertTrue(vm.server.closeFrameReceived.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `a connection that cannot open leaves nothing behind`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val candidate = TrackingCandidate(VmServiceUri.parse("ws://127.0.0.1:$freePort/HXKQJZK_Rkw=/ws"), true)

        assertThrows<VmServiceException> { session.connect(candidate) }

        assertEquals(1, candidate.released)
        assertTrue(session.state is SessionState.Disconnected)
        assertNull(session.client)
    }

    @Test
    fun `connecting twice is refused`() {
        session.connect(pasted())
        assertThrows<IllegalStateException> { session.connect(pasted()) }
        assertNotNull(session.client)
    }
}
