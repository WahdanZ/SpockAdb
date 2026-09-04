package spock.adb.device

import com.android.ddmlib.IDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class DeviceListerTest {

    private fun device(serial: String): IDevice = mockk(relaxed = true) {
        every { serialNumber } returns serial
    }

    private fun info(serial: String) = DeviceInfo(
        serialNumber = serial,
        model = "Pixel 7",
        manufacturer = "Google",
        androidVersion = "14",
        apiLevel = 34,
        abi = "arm64-v8a",
        isEmulator = false,
        state = DeviceState.ONLINE,
    )

    @Test
    fun `maps every device the bridge reports`() {
        val lister = DeviceLister(
            devicesSupplier = { listOf(device("A"), device("B")) },
            readInfo = { info(it.serialNumber) },
        )

        assertEquals(listOf("A", "B"), lister.list().map { it.serialNumber })
    }

    @Test
    fun `reports and swallows a failing device lookup instead of throwing`() {
        // The regression: AndroidSdkUtils.getDebugBridge asserts the EDT, so calling it from
        // a pooled thread threw. The exception escaped the background task, the UI callback
        // never ran, and the device dropdown stayed empty with nothing logged.
        val errors = mutableListOf<String>()
        val lister = DeviceLister(
            devicesSupplier = { error("Access is allowed from Event Dispatch Thread only") },
            onError = { message, _ -> errors += message },
        )

        val result = assertDoesNotThrow { lister.list() }

        assertTrue(result.isEmpty())
        assertEquals(1, errors.size, "the failure must be reported, not swallowed")
    }

    @Test
    fun `drops only the device whose properties cannot be read`() {
        val errors = mutableListOf<String>()
        val lister = DeviceLister(
            devicesSupplier = { listOf(device("good"), device("bad")) },
            readInfo = { d -> if (d.serialNumber == "bad") error("device went away") else info(d.serialNumber) },
            onError = { message, _ -> errors += message },
        )

        assertEquals(listOf("good"), lister.list().map { it.serialNumber })
        assertEquals(1, errors.size)
    }

    @Test
    fun `returns empty when ADB has not started yet`() {
        val errors = mutableListOf<String>()
        val lister = DeviceLister(devicesSupplier = { null }, onError = { m, _ -> errors += m })

        assertTrue(lister.list().isEmpty())
        // A bridge that is not ready yet is normal at startup, not an error to report.
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `returns empty when no devices are attached`() {
        assertTrue(DeviceLister(devicesSupplier = { emptyList() }).list().isEmpty())
    }
}
