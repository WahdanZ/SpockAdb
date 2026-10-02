package spock.adb.flutter.analysis

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterEvent
import spock.adb.flutter.vmservice.VmServiceEvent

/** The one place the analyzers meet the session's event type. */
class FlutterExtensionEventTest {

    private fun vmEvent(json: String) = VmServiceEvent("Extension", JsonParser.parseString(json).asJsonObject)

    @Test
    fun `an Extension event carries its kind, device time, isolate, data and history flag`() {
        val json = javaClass.getResource("/vmservice/event-frame.json")!!.readText()

        val event = FlutterExtensionEvent.from(FlutterEvent(vmEvent(json), history = true))!!

        assertEquals(FlutterExtensionEvent.FRAME, event.kind)
        assertEquals(1727776802000, event.timestampMs)
        assertEquals("isolates/1111", event.isolateId)
        assertTrue(event.history)
        assertEquals(42L, FrameStats.read(event)!!.number)
    }

    @Test
    fun `anything without a kind, a timestamp or a data object is not one`() {
        val noData = """{"kind": "Extension", "extensionKind": "Flutter.Frame", "timestamp": 1}"""
        val noTime = """{"kind": "Extension", "extensionKind": "Flutter.Frame", "extensionData": {}}"""
        val notExtension = """{"kind": "IsolateStart", "timestamp": 1}"""

        listOf(noData, noTime, notExtension).forEach { json ->
            assertNull(FlutterExtensionEvent.from(FlutterEvent(vmEvent(json), history = false)), json)
        }
    }
}
