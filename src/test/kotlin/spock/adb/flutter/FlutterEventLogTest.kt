package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.vmservice.VmServiceEvent

class FlutterEventLogTest {

    private val log = FlutterEventLog(errorCapacity = 3, frameCapacity = 5, navigationCapacity = 2)
    private val session = FlutterSession()
    private val identity =
        AppIdentity("emulator-5554", "spock.adb.spock_flutter_sample", 4242, IdentityCheck.DTD_PID_START)

    @Test
    fun `each kind is bounded on its own, so frames never push out an error`() {
        connect(session)
        accept(FlutterExtensionEvent.ERROR, 1)
        repeat(50) { accept(FlutterExtensionEvent.FRAME, 100L + it) }
        repeat(4) { accept(FlutterExtensionEvent.NAVIGATION, 200L + it) }

        val kept = checkNotNull(log.contents(session))

        assertEquals(listOf(1L), kept.errors.map { it.timestampMs })
        assertEquals((145L..149L).toList(), kept.frames.map { it.timestampMs })
        assertEquals(listOf(202L, 203L), kept.navigation.map { it.timestampMs })
        assertEquals(mapOf(FlutterExtensionEvent.FRAME to 45, FlutterExtensionEvent.NAVIGATION to 2), kept.dropped)
    }

    @Test
    fun `what DDS replayed before the session was current is kept, marked as history`() {
        log.sessionCreated(session)
        accept(FlutterExtensionEvent.ERROR, 1, history = true)
        log.sessionChanged(FlutterSessionChange.Connected(session, identity))
        accept(FlutterExtensionEvent.ERROR, 2)

        val errors = checkNotNull(log.contents(session)).errors

        assertEquals(listOf(true, false), errors.map { it.history })
    }

    @Test
    fun `a new session starts over, and a session that never became current leaves nothing`() {
        connect(session)
        accept(FlutterExtensionEvent.ERROR, 1)
        val refused = FlutterSession()
        log.sessionCreated(refused)
        log.accept(refused, event(FlutterExtensionEvent.ERROR, 2))
        val next = FlutterSession()
        log.sessionCreated(next)
        log.sessionChanged(FlutterSessionChange.Replaced(session, identity, next, identity))

        assertNull(log.contents(session), "the old session's events are gone with it")
        assertNull(log.contents(refused))
        assertEquals(emptyList<FlutterExtensionEvent>(), checkNotNull(log.contents(next)).errors)
    }

    @Test
    fun `events that are not errors, frames or routes are not kept`() {
        connect(session)
        log.accept(session, event("Flutter.RebuiltWidgets", 1))
        val exit = VmServiceEvent("Isolate", JsonObject().apply { addProperty("kind", "IsolateExit") })
        log.accept(session, FlutterEvent(exit, false))

        val kept = checkNotNull(log.contents(session))
        assertEquals(0, kept.errors.size + kept.frames.size + kept.navigation.size)
    }

    @Test
    fun `a disconnect keeps what was said`() {
        connect(session)
        accept(FlutterExtensionEvent.ERROR, 1)
        log.sessionChanged(FlutterSessionChange.Disconnected(session, identity, "lost"))

        assertEquals(1, checkNotNull(log.contents(session)).errors.size)
    }

    private fun connect(session: FlutterSession) {
        log.sessionCreated(session)
        log.sessionChanged(FlutterSessionChange.Connected(session, identity))
    }

    private fun accept(kind: String, timestamp: Long, history: Boolean = false) =
        log.accept(session, event(kind, timestamp, history))

    private fun event(kind: String, timestamp: Long, history: Boolean = false) = FlutterEvent(
        VmServiceEvent(
            "Extension",
            JsonObject().apply {
                addProperty("kind", "Extension")
                addProperty("extensionKind", kind)
                addProperty("timestamp", timestamp)
                add("extensionData", JsonObject())
            },
        ),
        history,
    )

    @Test
    fun `a session created after the current one keeps what it heard`() {
        val first = FlutterSession()
        val second = FlutterSession()
        log.sessionCreated(first)
        log.sessionCreated(second)
        log.accept(second, event(FlutterExtensionEvent.ERROR, 7, history = true))
        log.sessionChanged(FlutterSessionChange.Connected(first, identity))
        log.sessionChanged(FlutterSessionChange.Replaced(first, identity, second, identity))

        assertEquals(listOf(7L), checkNotNull(log.contents(second)).errors.map { it.timestampMs })
    }
}
