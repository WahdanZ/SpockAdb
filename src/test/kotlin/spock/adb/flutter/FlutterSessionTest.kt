package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.flutter.vmservice.ConnectionKind
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.HTTP_LOGGING
import spock.adb.flutter.vmservice.FakeVmService.Companion.STRUCTURED_ERRORS
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.fixture
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceSource
import spock.adb.flutter.vmservice.VmServiceUri
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FlutterSessionTest : FlutterSessionFixture() {

    @Test
    fun `connect picks the isolate behind the Flutter view and reads the VM`() {
        session.connect(pasted())

        assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
        assertEquals(UI_ISOLATE, session.uiIsolateId)
        assertTrue(session.selection is IsolateSelection.Selected)
        assertEquals(connectedAt, session.connectedAt)
        assertEquals("VM", session.vm?.get("type")?.asString)
        assertEquals(FlutterBuild.DEBUG, session.buildMode)
        assertEquals(ConnectionKind.DDS, session.connectionKind)
        assertEquals(ConnectionKind.DDS, session.snapshot.connectionKind)
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
        vm.replayOnListen = { stream ->
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
        // dart:io registers first; until a Flutter extension says debug or profile, nothing is switched.
        eventually(message = "the mode not known yet") {
            session.snapshot.httpRecording == HttpRecording.Off(HttpRecording.Reason.RELEASE_OR_UNKNOWN_MODE)
        }
        assertEquals(emptyList<String>(), writes(HTTP_LOGGING, restarted))
        assertNull(session.structuredErrorsEnabled)

        vm.pushEvent(
            "Isolate",
            isolateEvent("ServiceExtensionAdded", restarted, connectedAt + 1_002) {
                addProperty("extensionRPC", STRUCTURED_ERRORS)
            },
        )
        eventually(message = "structuredErrors read on the new isolate") { session.structuredErrorsEnabled == true }
        // The write reaches the app before its answer reaches the session: wait for the session.
        eventually(message = "logging on the new isolate") {
            session.snapshot.httpRecording == HttpRecording.EnabledBySpock
        }
        assertEquals(listOf("true"), writes(HTTP_LOGGING, restarted))
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
    fun `replayed events are marked as history and repeats are dropped`() {
        val older = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt - 2_000) }
        val old = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt - 1_000) }
        val live = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt + 1_000) }
        vm.replayOnListen = { stream ->
            if (stream == "Extension") listOf(older, old, old).forEach { vm.pushEvent("Extension", it) }
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
        vm.afterStreamListen = { stream -> if (stream == "Extension") vm.pushEvent("Extension", live) }

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
    fun `close disconnects, closes the client and releases the candidate once`() {
        val candidate = tracking()
        session.connect(candidate)
        val client = session.client!!

        session.close()
        session.close()

        assertEquals(SessionState.Disconnected("closed by Spock"), session.state)
        assertFalse(client.isOpen)
        assertEquals(1, candidate.released)
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a connection that cannot open leaves nothing behind`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val candidate = TrackingCandidate(VmServiceUri.parse("ws://127.0.0.1:$freePort/FAKEtoken_0000=/ws"), true)

        val error = assertThrows<VmServiceException> { session.connect(candidate) }

        assertEquals(1, candidate.released)
        assertEquals(SessionState.Disconnected(error.message!!), session.state)
        assertTrue(error.message!!.startsWith("Could not connect"), error.message)
        assertNull(session.client)
    }

    @Test
    fun `close during a stuck re-selection returns at once`() {
        session.connect(pasted())
        vm.on("_flutter.listViews") { FakeVmService.Reply.None }
        vm.pushEvent("Isolate", isolateEvent("IsolateExit", UI_ISOLATE, connectedAt + 1_000))
        vm.awaitRequests("_flutter.listViews", count = 2)

        val started = System.nanoTime()
        session.close()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(tookMs < 2_000, "close took $tookMs ms behind a re-selection waiting 5 s")
        assertEquals(SessionState.Disconnected(FlutterSession.CLOSED_BY_SPOCK), session.state)
    }

    @Test
    fun `a listener that waits on a thread calling back into the session does not deadlock it`() {
        val stuck = AtomicBoolean(false)
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    if (state !is SessionState.Paused) return
                    val other = Thread {
                        session.selectIsolate(UI_ISOLATE)
                        session.close()
                    }
                    other.start()
                    other.join(5_000)
                    stuck.set(other.isAlive)
                }
            },
        )
        session.connect(pasted())

        vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, connectedAt + 1_000))

        eventually(timeoutMs = 8_000, message = "the close from the other thread") {
            session.state is SessionState.Disconnected
        }
        assertFalse(stuck.get())
    }

    @Test
    fun `a listener that throws while connect reports a state breaks neither the connect nor a retry`() {
        val thrown = AtomicBoolean(false)
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    if (thrown.compareAndSet(false, true)) error("listener bug")
                }
            },
        )

        session.connect(pasted())
        val client = session.client!!

        assertTrue(thrown.get())
        assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
        session.close()
        assertFalse(client.isOpen)
        assertTrue(vm.server.closeFrameReceived.await(2, TimeUnit.SECONDS))
        session.connect(pasted())
        assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
    }

    @Test
    fun `a release that throws still ends the session, disconnected and closed`() {
        val uri = VmServiceUri.parse(vm.uri)
        val candidate = object : VmServiceCandidate(VmServiceSource.LOGCAT, ddsLikely = true) {
            override val description: String get() = uri.redacted()
            override fun open(): VmServiceUri = uri
            override fun release() = error("adb is gone")
        }
        session.connect(candidate)
        val client = session.client!!

        session.close()

        assertEquals(SessionState.Disconnected(FlutterSession.CLOSED_BY_SPOCK), session.state)
        assertFalse(client.isOpen)
        assertNull(session.client)
        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
    }

    @Test
    fun `a burst of extension registrations while no isolate is chosen costs one re-selection`() {
        vm.viewIsolates = null
        vm.isolates.clear()
        vm.addIsolate("isolates/0001", listOf("ext.dart.io.getVersion"))
        session.connect(pasted())
        val before = vm.requestsFor("_flutter.listViews").size

        repeat(30) { index ->
            vm.pushEvent(
                "Isolate",
                isolateEvent("ServiceExtensionAdded", "isolates/0001", connectedAt + index) {
                    addProperty("extensionRPC", "ext.dart.io.extension$index")
                },
            )
        }
        awaitEventsHandled()
        Thread.sleep(400)

        val reselections = vm.requestsFor("_flutter.listViews").size - before
        assertTrue(reselections in 1..3, "$reselections re-selections for one burst")
    }

    @Test
    fun `while several isolates wait for the caller's choice, isolate events do not re-select`() {
        vm.viewIsolates = listOf(UI_ISOLATE, "isolates/3333")
        val rpcs = fixture("getIsolate-ui-debug.json").getAsJsonArray("extensionRPCs").map { it.asString }
        vm.addIsolate("isolates/3333", rpcs)
        session.connect(pasted())
        val before = vm.requestsFor("_flutter.listViews").size

        vm.pushEvent("Isolate", isolateEvent("IsolateStart", "isolates/4444", connectedAt + 1_000))
        vm.pushEvent(
            "Isolate",
            isolateEvent("ServiceExtensionAdded", "isolates/3333", connectedAt + 1_001) {
                addProperty("extensionRPC", "ext.flutter.extra")
            },
        )
        vm.pushEvent("Debug", isolateEvent("Resume", "isolates/3333", connectedAt + 1_002))
        awaitEventsHandled()
        Thread.sleep(300)

        assertEquals(before, vm.requestsFor("_flutter.listViews").size)
        assertTrue(session.selection is IsolateSelection.Ambiguous)
    }

    @Test
    fun `an extension registered while the UI isolate is paused is not called until it resumes`() {
        vm.addIsolate(UI_ISOLATE, listOf(HTTP_LOGGING))
        session.connect(pasted())
        vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, connectedAt + 1_000))
        eventually { session.state is SessionState.Paused }

        vm.pushEvent(
            "Isolate",
            isolateEvent("ServiceExtensionAdded", UI_ISOLATE, connectedAt + 1_001) {
                addProperty("extensionRPC", STRUCTURED_ERRORS)
            },
        )
        awaitEventsHandled()
        Thread.sleep(300)
        assertEquals(0, extensionCalls(STRUCTURED_ERRORS).size)

        vm.pushEvent("Debug", isolateEvent("Resume", UI_ISOLATE, connectedAt + 1_002))
        eventually(message = "the read after resuming") { session.structuredErrorsEnabled == true }
        assertEquals(SessionState.Connected(UI_ISOLATE), session.state)
    }

    @Test
    fun `live events drive the session whatever the device clock says`() {
        session.connect(pasted())
        // The device's clock an hour behind the host's.
        val behind = connectedAt - 3_600_000

        vm.pushEvent("Debug", isolateEvent("PauseBreakpoint", UI_ISOLATE, behind))
        eventually(message = "the pause") { session.state == SessionState.Paused(UI_ISOLATE, "PauseBreakpoint") }
        vm.pushEvent("Debug", isolateEvent("Resume", UI_ISOLATE, behind + 1))
        eventually(message = "the resume") { session.state == SessionState.Connected(UI_ISOLATE) }
        vm.pushEvent("Extension", stateChanged("false", behind + 2))
        eventually(message = "the live change") { session.structuredErrorsEnabled == false }

        assertFalse(events.any { it.history }, events.toString())
    }

    @Test
    fun `a replay is history whatever the device clock says`() {
        // The device's clock an hour ahead of the host's.
        val ahead = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt + 3_600_000) }
        vm.replayOnListen = { stream -> if (stream == "Extension") vm.pushEvent("Extension", ahead) }

        session.connect(pasted())

        eventually(message = "the replayed frame") { events.any { it.extensionKind == "Flutter.Frame" } }
        assertTrue(events.single { it.extensionKind == "Flutter.Frame" }.history)
    }

    @Test
    fun `a reconnect's replay of events already delivered is dropped`() {
        val old = fixture("event-frame.json").apply { addProperty("timestamp", connectedAt - 1_000) }
        vm.replayOnListen = { stream -> if (stream == "Extension") vm.pushEvent("Extension", old) }
        session.connect(pasted())
        eventually(message = "the first replay") { events.size == 1 }
        vm.server.drop()
        eventually { session.state is SessionState.Disconnected }

        session.connect(pasted())
        awaitEventsHandled()

        assertEquals(1, events.count { it.timestamp == connectedAt - 1_000 })
    }

    @Test
    fun `a listener told Connected finds the snapshot set up`() {
        val seen = CopyOnWriteArrayList<FlutterSessionSnapshot>()
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    if (state is SessionState.Connected && state.isolateId != null) seen += session.snapshot
                }
            },
        )

        session.connect(pasted())

        val snapshot = seen.first()
        assertEquals(SessionState.Connected(UI_ISOLATE), snapshot.state)
        assertEquals(UI_ISOLATE, snapshot.uiIsolateId)
        assertEquals(true, snapshot.structuredErrorsEnabled)
    }

    @Test
    fun `connecting twice is refused`() {
        session.connect(pasted())
        assertThrows<IllegalStateException> { session.connect(pasted()) }
        assertNotNull(session.client)
    }
}
