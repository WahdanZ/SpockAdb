package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.vmservice.FakeVmService
import spock.adb.flutter.vmservice.FakeVmService.Companion.HTTP_LOGGING
import spock.adb.flutter.vmservice.FakeVmService.Companion.UI_ISOLATE
import spock.adb.flutter.vmservice.FakeVmService.Companion.eventually
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.VmServiceClient
import java.util.concurrent.CopyOnWriteArrayList

/** HTTP timeline logging: on only where allowed, and off on close only where Spock switched it on [FR10]. */
class FlutterSessionHttpRecordingTest : FlutterSessionFixture() {

    @Test
    fun `HTTP logging that was off is switched on, and off again on close`() {
        session.connect(pasted())

        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertTrue(vm.httpLogging)
        assertEquals(HttpRecording.EnabledBySpock, session.snapshot.httpRecording)

        session.close()

        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `HTTP logging that was already on is left alone, on connect and on close`() {
        vm.httpLogging = true

        session.connect(pasted())
        assertEquals(HttpRecording.AlreadyOn, session.snapshot.httpRecording)
        session.close()

        assertEquals(1, extensionCalls(HTTP_LOGGING).size)
        assertEquals(emptyList<String>(), writes(HTTP_LOGGING))
        assertTrue(vm.httpLogging)
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
    fun `a switch-on that times out may still apply, so close still switches it off`() {
        vm.on(HTTP_LOGGING) { params ->
            when (params.get("enabled")?.asString) {
                // Applied by the app, but the answer never comes.
                "true" -> FakeVmService.Reply.None.also { vm.httpLogging = true }
                else -> {
                    params.get("enabled")?.let { vm.httpLogging = it.asString == "true" }
                    FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", vm.httpLogging) })
                }
            }
        }
        val impatient = FlutterSession(connector = { VmServiceClient.connect(it, timeoutMs = 300) })
        try {
            impatient.connect(pasted())
            assertEquals(off(HttpRecording.Reason.FAILED), impatient.snapshot.httpRecording)
        } finally {
            impatient.close()
        }

        assertEquals(listOf("true", "false"), writes(HTTP_LOGGING, UI_ISOLATE))
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `with recording switched off in the settings, HTTP logging is not even read`() {
        session.connect(pasted(), recordHttp = false)
        session.close()

        assertEquals(off(HttpRecording.Reason.SETTING_OFF), lastRecording())
        assertEquals(0, extensionCalls(HTTP_LOGGING).size)
        assertFalse(vm.httpLogging)
    }

    @Test
    fun `a direct connection kept by opt-in leaves HTTP logging alone`() {
        vm.dds = false

        session.connect(pasted(), allowDirect = true)
        session.close()

        assertEquals(off(HttpRecording.Reason.NOT_DDS), lastRecording())
        assertEquals(0, extensionCalls(HTTP_LOGGING).size)
    }

    @Test
    fun `an isolate whose build mode is not known yet is left until a Flutter extension says debug or profile`() {
        vm.addIsolate(UI_ISOLATE, listOf(HTTP_LOGGING))

        session.connect(pasted())

        assertEquals(off(HttpRecording.Reason.RELEASE_OR_UNKNOWN_MODE), session.snapshot.httpRecording)
        assertEquals(0, extensionCalls(HTTP_LOGGING).size)

        vm.addIsolate(UI_ISOLATE, listOf(HTTP_LOGGING, "ext.flutter.exit"))
        vm.pushEvent(
            "Isolate",
            isolateEvent("ServiceExtensionAdded", UI_ISOLATE, connectedAt + 1_000) {
                addProperty("extensionRPC", "ext.flutter.exit")
            },
        )

        eventually(message = "logging on once the build reads as profile") {
            session.snapshot.httpRecording == HttpRecording.EnabledBySpock
        }
        assertEquals(FlutterBuild.PROFILE, session.buildMode)
        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
    }

    @Test
    fun `a refused switch-on is reported, and close does not switch off what it never switched on`() {
        vm.on(HTTP_LOGGING) { params ->
            if (params.has("enabled")) {
                FakeVmService.Reply.Error(-32_000, "refused")
            } else {
                FakeVmService.Reply.Result(JsonObject().apply { addProperty("enabled", false) })
            }
        }

        session.connect(pasted())

        assertEquals(off(HttpRecording.Reason.FAILED), session.snapshot.httpRecording)
        session.close()
        assertEquals(listOf("true"), writes(HTTP_LOGGING, UI_ISOLATE))
    }

    private fun off(reason: HttpRecording.Reason) = HttpRecording.Off(reason)

    /** The snapshot drops it on close, so it is read as the session reported it while connected. */
    private fun lastRecording(): HttpRecording? = recordings.lastOrNull()

    private val recordings = CopyOnWriteArrayList<HttpRecording?>().also { seen ->
        session.addListener(
            object : FlutterSessionListener {
                override fun onEvent(event: FlutterEvent) = Unit
                override fun onStateChanged(state: SessionState) {
                    if (state is SessionState.Connected && state.isolateId != null) {
                        seen += session.snapshot.httpRecording
                    }
                }
            },
        )
    }
}
