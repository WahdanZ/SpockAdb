package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import io.mockk.mockk
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.device.DeviceState
import spock.adb.mcp.tools.ToolContext

/**
 * A ToolContext that needs no IDE and no device, so the protocol and the safety model can be
 * tested for real rather than only by inspection.
 */
class FakeToolContext(
    override val project: Project? = null,
    private val available: List<ConnectedDevice> = listOf(device("emulator-5554")),
    private val applicationId: String? = "com.example.app",
    /** Answer given to every destructive confirmation. */
    var confirmationAnswer: Boolean = false,
) : ToolContext {

    val confirmations = mutableListOf<String>()
    private var selected: String? = null

    override fun devices(): List<ConnectedDevice> = available

    override fun requireDevice(serialOverride: String?): ConnectedDevice {
        val wanted = serialOverride ?: selected
        return available.firstOrNull { it.serialNumber == wanted }
            ?: available.firstOrNull()
            ?: error("No Android devices are connected.")
    }

    override fun selectDevice(serial: String): ConnectedDevice {
        val device = available.firstOrNull { it.serialNumber == serial }
            ?: error("No connected device has serial '$serial'.")
        selected = serial
        return device
    }

    override fun confirmDestructive(
        toolName: String,
        summary: String,
        device: ConnectedDevice,
    ): Boolean {
        confirmations += toolName
        return confirmationAnswer
    }

    override fun projectApplicationId(): String? = applicationId

    companion object {
        fun device(serial: String, state: DeviceState = DeviceState.ONLINE): ConnectedDevice =
            ConnectedDevice(
                device = mockk<IDevice>(relaxed = true),
                info = DeviceInfo(
                    serialNumber = serial,
                    model = "Pixel 7",
                    manufacturer = "Google",
                    androidVersion = "14",
                    apiLevel = 34,
                    abi = "arm64-v8a",
                    isEmulator = serial.startsWith("emulator"),
                    state = state,
                ),
            )
    }
}
