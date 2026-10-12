package spock.adb.mcp

import com.android.ddmlib.AndroidDebugBridge
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.FindUiElementTool
import spock.adb.mcp.tools.TapElementTool
import spock.adb.mcp.tools.ToolContent
import java.util.concurrent.TimeUnit

/**
 * Not a unit test: the #182 F-14 timing check of a find followed at once by a tap, on a real
 * device, through the tools themselves. Skipped unless `SPOCK_LIVE_SERIAL` names a connected
 * device:
 *
 * ```
 * SPOCK_LIVE_SERIAL=emulator-5554 SPOCK_LIVE_SELECTOR='{"text":"Navigation"}' \
 *   ./gradlew test --tests spock.adb.mcp.UiReadLiveCheck --rerun -i
 * ```
 *
 * Each round finds the selector, taps it at once, and presses Back, so put the screen that holds
 * it in front. It prints how long each call took and the first line of each answer.
 */
@EnabledIfEnvironmentVariable(named = UiReadLiveCheck.SERIAL_ENV, matches = ".+")
class UiReadLiveCheck {

    @Test
    fun `a find and then a tap, timed`() {
        val device = connect(System.getenv(SERIAL_ENV))
        val context = FakeToolContext(available = listOf(device))
        val selector = JsonParser.parseString(System.getenv(SELECTOR_ENV) ?: DEFAULT_SELECTOR).asJsonObject
        repeat(ROUNDS) { round ->
            val find = timed("find", FindUiElementTool(), selector, context)
            val tap = timed("tap", TapElementTool(), selector, context)
            println("== round ${round + 1}: find $find ms, tap $tap ms, together ${find + tap} ms")
            device.device.executeShellCommand("input keyevent KEYCODE_BACK", NullReceiver, 5, TimeUnit.SECONDS)
            Thread.sleep(SETTLE_MS)
        }
    }

    private fun timed(label: String, tool: AdbTool, arguments: JsonObject, context: FakeToolContext): Long {
        val started = System.nanoTime()
        val result = tool.execute(arguments.deepCopy(), context)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val text = (result.content.first() as ToolContent.Text).text
        println("   $label: $ms ms, ${text.length} chars, error=${result.isError}")
        text.lineSequence().take(PRINTED_LINES).forEach { println("     $it") }
        return ms
    }

    private fun connect(serial: String): ConnectedDevice {
        AndroidDebugBridge.init(false)
        val adb = System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" } ?: "adb"
        val bridge = AndroidDebugBridge.createBridge(adb, false, BRIDGE_TIMEOUT_S, TimeUnit.SECONDS)
            ?: error("no adb bridge")
        val deadline = System.currentTimeMillis() + BRIDGE_TIMEOUT_S * 1_000
        while (!bridge.hasInitialDeviceList() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        val device = bridge.devices.firstOrNull { it.serialNumber == serial } ?: error("$serial is not connected")
        return ConnectedDevice(device, DeviceInfo.unknown(serial))
    }

    private object NullReceiver : com.android.ddmlib.IShellOutputReceiver {
        override fun addOutput(data: ByteArray, offset: Int, length: Int) = Unit
        override fun flush() = Unit
        override fun isCancelled() = false
    }

    companion object {
        const val SERIAL_ENV = "SPOCK_LIVE_SERIAL"
        const val SELECTOR_ENV = "SPOCK_LIVE_SELECTOR"
        private const val DEFAULT_SELECTOR = """{"text":"Navigation","exact":true}"""
        private const val ROUNDS = 3
        private const val SETTLE_MS = 1_500L
        private const val PRINTED_LINES = 3
        private const val NANOS_PER_MS = 1_000_000L
        private const val BRIDGE_TIMEOUT_S = 10L
        private const val POLL_MS = 100L
    }
}
