package spock.adb.flutter

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.vmservice.FakeVmService.Companion.isolateEvent
import spock.adb.flutter.vmservice.VmServiceEvent

class EventHistoryTest {

    private val history = EventHistory(capacity = 3)

    private fun log(sequence: Int, timestamp: Long, replayed: Boolean, message: String = "same text") =
        VmServiceEvent(
            "Logging",
            isolateEvent("Logging", "isolates/1", timestamp) {
                add(
                    "logRecord",
                    JsonObject().apply {
                        addProperty("sequenceNumber", sequence)
                        add("message", JsonObject().apply { addProperty("valueAsString", message) })
                    },
                )
            },
            duringListen = replayed,
        )

    @Test
    fun `an event that arrived while its stream was being subscribed is history, whatever its timestamp`() {
        // Far ahead of any host clock, yet history: it arrived during the subscription.
        assertTrue(history.accept(log(1, timestamp = Long.MAX_VALUE / 2, replayed = true))!!.history)
        history.newConnection()
        // Far behind any host clock, yet live: it arrived after.
        assertFalse(history.accept(log(2, timestamp = 1, replayed = false))!!.history)
    }

    @Test
    fun `isolate and debug events are never history, so they always drive the session`() {
        val pause = VmServiceEvent("Debug", isolateEvent("PauseBreakpoint", "isolates/1", 5), duringListen = true)
        val exit = VmServiceEvent("Isolate", isolateEvent("IsolateExit", "isolates/1", 5), duringListen = true)

        assertFalse(history.accept(pause)!!.history)
        assertFalse(history.accept(exit)!!.history)
        assertNotNull(history.accept(pause), "a repeated pause is still a pause")
    }

    @Test
    fun `a replayed repeat is dropped, a live one never is`() {
        assertNotNull(history.accept(log(1, 100, replayed = true)))
        assertNull(history.accept(log(1, 100, replayed = true)))
        assertNotNull(history.accept(log(1, 100, replayed = false)))
    }

    @Test
    fun `records in one millisecond with the same text are told apart by their sequence number`() {
        assertNotNull(history.accept(log(1, 100, replayed = true)))
        assertNotNull(history.accept(log(2, 100, replayed = true)))
        assertNotNull(history.accept(log(3, 100, replayed = true, message = "other text")))
    }

    @Test
    fun `older than the newest replayed event on its stream is history too, until the next connection`() {
        history.accept(log(1, 500, replayed = true))

        assertTrue(history.accept(log(2, 400, replayed = false))!!.history)
        assertFalse(history.accept(log(3, 600, replayed = false))!!.history)

        history.newConnection()
        assertFalse(history.accept(log(4, 450, replayed = false))!!.history)
    }

    @Test
    fun `what was seen stays bounded, the eldest forgotten first`() {
        (1..4).forEach { assertNotNull(history.accept(log(it, 100L + it, replayed = true))) }
        history.newConnection()

        assertNotNull(history.accept(log(1, 101, replayed = true)), "the eldest was evicted")
        assertNull(history.accept(log(4, 104, replayed = true)), "the newest is still known")
    }
}
