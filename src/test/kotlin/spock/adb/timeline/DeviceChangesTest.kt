package spock.adb.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState

class DeviceChangesTest {

    private fun info(serial: String, state: DeviceState = DeviceState.ONLINE) = DeviceInfo(
        serialNumber = serial,
        model = "Pixel 7",
        manufacturer = "Google",
        androidVersion = "14",
        apiLevel = 34,
        abi = "arm64-v8a",
        isEmulator = false,
        state = state,
    )

    @Test
    fun `connects, disconnects and state changes each become an event`() {
        val events = DebugTimelineService.deviceChanges(
            before = mapOf("A" to info("A"), "B" to info("B", DeviceState.UNAUTHORIZED)),
            after = mapOf("B" to info("B"), "C" to info("C")),
            nowMs = 5,
        )

        assertEquals(
            listOf(
                "Connected: Google Pixel 7",
                "Disconnected: Google Pixel 7",
                "Google Pixel 7: unauthorized → online"
            ),
            events.map { it.title },
        )
        assertEquals(listOf("C", "A", "B"), events.map { it.deviceSerial })
        assertEquals(TimelineSeverity.WARNING, events[1].severity)
    }

    @Test
    fun `the same list is no change`() {
        val devices = mapOf("A" to info("A"))

        assertEquals(emptyList<TimelineEvent>(), DebugTimelineService.deviceChanges(devices, devices))
    }
}
