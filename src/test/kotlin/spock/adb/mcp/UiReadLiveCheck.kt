package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import spock.adb.device.ConnectedDevice
import spock.adb.device.DeviceInfo
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.FindUiElementTool
import spock.adb.mcp.tools.OpenDeepLinkTool
import spock.adb.mcp.tools.RouteReader
import spock.adb.mcp.tools.RunRecipeTool
import spock.adb.mcp.tools.SessionRouteReader
import spock.adb.mcp.tools.TapElementTool
import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolRegistry
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
        val device = throughAdbClient(System.getenv(SERIAL_ENV))
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

    /**
     * #182 F-13 on the Flutter sample, from its hub, with `SPOCK_VM_SERVICE_URI` set as for
     * `NavigatorLiveCheck`: taps and a deep link with `expectRoute`, read through that session.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = URI_ENV, matches = ".+")
    fun `expectRoute against the Flutter sample`() {
        val device = throughAdbClient(System.getenv(SERIAL_ENV))
        val context = FakeToolContext(available = listOf(device))
        val session = FlutterSession()
        session.connect(PastedUriDiscovery(System.getenv(URI_ENV)).discover().single(), false)
        try {
            val routes = RouteReader { _, _, _, check -> SessionRouteReader.read(session, check) }
            val tap = TapElementTool(routes)
            val calls = listOf(
                Triple(
                    "hub → /navigation",
                    tap,
                    """{"testTag":"hub_navigation","exactTag":true,"expectRoute":"/navigation"}""",
                ),
                Triple("push /nav/a", tap, """{"text":"Push /nav/a","exact":true,"expectRoute":"/nav/a"}"""),
                Triple(
                    "push /nav/a/b, expect /nowhere",
                    tap,
                    """{"text":"Push /nav/a/b","exact":true,"expectRoute":"/nowhere","expectTimeoutMs":1000}""",
                ),
                Triple("deep link", OpenDeepLinkTool(routes), deepLink("item/42?ref=spock", "/item/42")),
                // No project, so no session: the real reader's answer.
                Triple("no session", OpenDeepLinkTool(), deepLink("item/7", "/item/7")),
                Triple("deep_link recipe", recipes(routes), RECIPE),
            )
            calls.forEach { (label, tool, json) ->
                timed(label, tool, JsonParser.parseString(json).asJsonObject, context)
            }
        } finally {
            session.close()
        }
    }

    /**
     * [serial] with each shell command run by the `adb` client, one process per command — a few
     * tens of ms more per command than ddmlib. Not a ddmlib bridge: its monitor thread fails the
     * platform's leaked-thread check, and stopping the bridge kills the adb server under `flutter run`.
     */
    private fun throughAdbClient(serial: String): ConnectedDevice {
        val adb = System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" } ?: "adb"
        val device = mockk<IDevice>(relaxed = true)
        every { device.serialNumber } returns serial
        every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
            val process = ProcessBuilder(adb, "-s", serial, "shell", firstArg<String>())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes()
            process.waitFor()
            secondArg<IShellOutputReceiver>().apply {
                addOutput(output, 0, output.size)
                flush()
            }
        }
        return ConnectedDevice(device, DeviceInfo.unknown(serial))
    }

    /** The recipe tool, its deep link step reading routes through [routes]. */
    private fun recipes(routes: RouteReader) = RunRecipeTool(
        findTool = { name ->
            if (name == "android_open_deep_link") OpenDeepLinkTool(routes) else ToolRegistry.find(name)
        },
        isToolEnabled = { true },
        record = {},
    )

    private fun deepLink(path: String, route: String) =
        """{"uri":"spockflutter://open/$path","packageName":"spock.adb.spock_flutter_sample","expectRoute":"$route"}"""

    private fun timed(label: String, tool: AdbTool, arguments: JsonObject, context: FakeToolContext): Long {
        val started = System.nanoTime()
        val result = tool.execute(arguments.deepCopy(), context)
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val text = (result.content.first() as ToolContent.Text).text
        println("   $label: $ms ms, ${text.length} chars, error=${result.isError}")
        // The first lines, and any verdict below them.
        text.lineSequence().filterIndexed { index, line -> index < PRINTED_LINES || VERDICTS.containsMatchIn(line) }
            .forEach { println("     ${it.take(PRINTED_WIDTH)}") }
        return ms
    }

    private object NullReceiver : IShellOutputReceiver {
        override fun addOutput(data: ByteArray, offset: Int, length: Int) = Unit
        override fun flush() = Unit
        override fun isCancelled() = false
    }

    companion object {
        const val SERIAL_ENV = "SPOCK_LIVE_SERIAL"
        const val SELECTOR_ENV = "SPOCK_LIVE_SELECTOR"
        const val URI_ENV = "SPOCK_VM_SERVICE_URI"
        private const val RECIPE = """{"recipe":"deep_link","params":{""" +
            """"uri":"spockflutter://open/item/43?ref=spock","packageName":"spock.adb.spock_flutter_sample",""" +
            """"expectRoute":"/item/43","expectText":"id=43"}}"""
        private const val DEFAULT_SELECTOR = """{"text":"Navigation","exact":true}"""
        private const val ROUNDS = 3
        private const val SETTLE_MS = 1_500L
        private const val PRINTED_LINES = 3

        /** A verdict, or a recipe step's line. */
        private val VERDICTS = Regex("^(VERIFIED|NOT OBSERVED|INCONCLUSIVE|CANCELLED|\\d+\\. [A-Z]{4,})")
        private const val PRINTED_WIDTH = 400
        private const val NANOS_PER_MS = 1_000_000L
    }
}
