package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.TimeoutException
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.ActionOutcome
import spock.adb.mcp.tools.CancellableToolContext
import spock.adb.mcp.tools.InputTextIntoElementTool
import spock.adb.mcp.tools.LongPressElementTool
import spock.adb.mcp.tools.TapElementTool
import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.UncancellableToolContext
import spock.adb.mcp.tools.WaitForElementTool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An action with an expected result: dispatched once, never repeated, and reported as verified
 * only when the result was not already there before it.
 */
class ElementActionsTest {

    /**
     * A device whose screen is the next of [dumps] at each capture, repeating the last. [onCommand]
     * runs before each command is answered, and may throw to fail it.
     */
    private class ScriptedScreen(private val dumps: List<String>, private val onCommand: (String) -> Unit = {}) {
        val commands = mutableListOf<String>()
        private var reads = 0
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                commands += command
                onCommand(command)
                val output = when {
                    command.startsWith("cat ") -> dumps[minOf(reads++, dumps.lastIndex)]
                    command == "wm size" -> "Physical size: 1080x2400"
                    command == "wm density" -> "Physical density: 420"
                    else -> ""
                }.toByteArray()
                secondArg<IShellOutputReceiver>().addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))

        val inputs: List<String> get() = commands.filter { it.startsWith("input ") }

        /** Screen captures begun after the first input was sent. */
        val capturesAfterInput: Int
            get() = commands.dropWhile { !it.startsWith("input ") }.count { it.startsWith("uiautomator dump") }
    }

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                else -> addProperty(key, value.toString())
            }
        }
    }

    private fun tap(device: ScriptedScreen, vararg pairs: Pair<String, Any>, context: ToolContext = device.context) =
        TapElementTool().execute(args("testTag" to "order_button", *pairs), context)

    @Test
    fun `an expectation met on the second look after the tap is verified, with one tap`() {
        val device = ScriptedScreen(listOf(screen(ORDER), screen(ORDER), screen(ORDER + STATUS)))

        val result = tap(device, "expectTestTag" to "order_status")

        assertFalse(result.isError, result.text())
        assertEquals(listOf("input tap 150 150"), device.inputs)
        val text = result.text()
        assertTrue(text.startsWith("Observed on emulator-5554"), text)
        assertTrue(text.contains("Tap dispatched once to 'Place order'"), text)
        assertTrue(text.contains("VERIFIED: testTag='order_status' is visible after 2 observation(s)"), text)
        assertTrue(text.contains("Before the tap it was not: nothing matched testTag='order_status'"), text)
        assertTrue(text.contains("After the tap: Observed on emulator-5554"), text)
        // The pre-action metrics are reused: the display is measured once for the whole call.
        assertEquals(1, device.commands.count { it == "wm size" }, device.commands.toString())
    }

    @Test
    fun `an expectation never met fails, saying the tap was dispatched once and not repeated`() {
        val device = ScriptedScreen(listOf(screen(ORDER)))

        val result = tap(device, "expectTestTag" to "order_status", "expectTimeoutMs" to 600)

        assertTrue(result.isError, result.text())
        val text = result.text()
        assertTrue(text.contains("NOT OBSERVED"), text)
        assertTrue(text.contains("dispatched once"), text)
        assertTrue(text.contains("not repeated"), text)
        assertEquals(listOf("input tap 150 150"), device.inputs)
    }

    @Test
    fun `an expectation that already held before the tap is inconclusive, and an error`() {
        val device = ScriptedScreen(listOf(screen(ORDER + STATUS)))

        val result = tap(device, "expectTestTag" to "order_status")

        assertTrue(result.isError, result.text())
        val text = result.text()
        assertTrue(text.contains("INCONCLUSIVE"), text)
        assertTrue(text.contains("already visible before the tap"), text)
        assertFalse(text.contains("VERIFIED"), text)
        assertEquals(listOf("input tap 150 150"), device.inputs)
    }

    @Test
    fun `a tap that times out is uncertain, sent once, and followed by nothing`() {
        val device = ScriptedScreen(listOf(screen(ORDER))) { command ->
            if (command.startsWith("input tap")) throw TimeoutException("timed out")
        }

        val result = tap(device, "expectTestTag" to "order_status")

        assertTrue(result.isError, result.text())
        val text = result.text()
        assertTrue(text.contains("Dispatch uncertain at the tap"), text)
        assertTrue(text.contains("did not finish within"), text)
        assertTrue(text.contains("may have reached the device and was not repeated"), text)
        assertTrue(text.contains("android_find_ui_element or android_wait_for_element"), text)
        assertEquals(1, device.inputs.size, device.commands.toString())
        assertEquals(0, device.capturesAfterInput, "no verification after an uncertain dispatch: ${device.commands}")
    }

    @Test
    fun `a lost device during the tap is uncertain too, not a claim it failed`() {
        val device = ScriptedScreen(listOf(screen(ORDER))) { command ->
            if (command.startsWith("input tap")) throw java.io.IOException("device offline")
        }

        val result = tap(device)

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("Dispatch uncertain"), result.text())
        assertTrue(result.text().contains("no longer available"), result.text())
    }

    @Test
    fun `text input whose focusing tap fails never types`() {
        val field = """<node class="android.widget.EditText" resource-id="name_field" package="p" clickable="true"
            focusable="true" enabled="true" bounds="[0,300][300,400]" />"""
        val device = ScriptedScreen(listOf(screen(field))) { command ->
            if (command.startsWith("input tap")) throw TimeoutException("timed out")
        }

        val result = InputTextIntoElementTool().execute(
            args("testTag" to "name_field", "value" to "hello"),
            device.context,
        )

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("Dispatch uncertain at the focusing tap"), result.text())
        assertTrue(result.text().contains("`input text` was never sent"), result.text())
        assertTrue(device.commands.none { it.startsWith("input text") }, device.commands.toString())
    }

    @Test
    fun `stop during the verification ends it as cancelled, after one tap`() {
        val stopped = AtomicBoolean(false)
        var tapped = false
        val device = ScriptedScreen(listOf(screen(ORDER))) { command ->
            // Stop lands during the first capture after the tap.
            if (command.startsWith("input tap")) tapped = true
            if (tapped && command.startsWith("uiautomator dump")) stopped.set(true)
        }
        val context = CancellableToolContext(device.context, stopped::get)

        val result = tap(device, "expectTestTag" to "order_status", "expectTimeoutMs" to 60_000, context = context)

        assertTrue(result.isError, result.text())
        val text = result.text()
        assertTrue(text.contains("CANCELLED while checking for testTag='order_status' to be visible"), text)
        assertTrue(text.contains("dispatched once"), text)
        assertEquals(listOf("input tap 150 150"), device.inputs)
    }

    @Test
    fun `stop during the tap itself says the input may or may not have landed`() {
        val stopped = AtomicBoolean(false)
        val device = ScriptedScreen(listOf(screen(ORDER))) { command ->
            if (command.startsWith("input tap")) stopped.set(true)
        }

        val context = CancellableToolContext(device.context, stopped::get)

        val result = tap(device, "expectTestTag" to "order_status", context = context)

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("CANCELLED during the tap"), result.text())
        assertTrue(result.text().contains("may or may not have reached the device"), result.text())
        assertEquals(1, device.inputs.size)
        assertEquals(0, device.capturesAfterInput)
    }

    @Test
    fun `stop while the target is still being found sends nothing, and says so`() {
        val stopped = AtomicBoolean(false)
        val device = ScriptedScreen(listOf(screen(ORDER))) { command ->
            if (command.startsWith("uiautomator dump")) stopped.set(true)
        }
        val context = CancellableToolContext(device.context, stopped::get)

        val result = tap(device, "expectTestTag" to "order_status", context = context)

        assertTrue(result.isError, result.text())
        assertTrue(result.text().startsWith("CANCELLED while finding testTag='order_button'"), result.text())
        assertTrue(result.text().contains("nothing was dispatched"), result.text())
        assertTrue(device.inputs.isEmpty(), device.commands.toString())
    }

    @Test
    fun `the expectation is matched over the whole screen, not the action's container`() {
        // The button sits in form_a; the status is outside it. Inheriting containerTag would never see it.
        val form = """<node class="android.view.View" resource-id="form_a" package="p" bounds="[0,0][1080,1000]">
            $ORDER</node>"""
        val device = ScriptedScreen(listOf(screen(form), screen(form + STATUS_BELOW)))

        val result = TapElementTool().execute(
            args("testTag" to "order_button", "containerTag" to "form_a", "expectTestTag" to "order_status"),
            device.context,
        )

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("VERIFIED"), result.text())
    }

    @Test
    fun `a long press checks its expectation the same way`() {
        val device = ScriptedScreen(listOf(screen(ORDER), screen(ORDER + STATUS)))

        val result = LongPressElementTool().execute(
            args("testTag" to "order_button", "expectTestTag" to "order_status"),
            device.context,
        )

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("Long press dispatched once"), result.text())
        assertTrue(result.text().contains("VERIFIED"), result.text())
        assertEquals(1, device.inputs.size)
    }

    @Test
    fun `a state expectation uses the wait's words`() {
        val off = """<node class="android.widget.Switch" resource-id="order_switch" package="p" checkable="true"
            checked="false" clickable="true" enabled="true" bounds="[0,500][300,600]" />"""
        val on = off.replace("checked=\"false\"", "checked=\"true\"")
        val device = ScriptedScreen(listOf(screen(ORDER + off), screen(ORDER + on)))

        val result = tap(device, "expectTestTag" to "order_switch", "expectUntil" to "checked")

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("VERIFIED: testTag='order_switch' is checked"), result.text())
    }

    @Test
    fun `expectation arguments without an element to expect are refused before the device is touched`() {
        val device = ScriptedScreen(listOf(screen(ORDER)))

        val error = assertThrows(IllegalArgumentException::class.java) {
            tap(device, "expectUntil" to "gone")
        }

        assertTrue(error.message!!.contains("expectUntil given without expectTestTag"), error.message)
        assertTrue(device.commands.isEmpty(), device.commands.toString())
    }

    @Test
    fun `an unknown expectUntil is refused before the device is touched`() {
        val device = ScriptedScreen(listOf(screen(ORDER)))

        val error = assertThrows(IllegalArgumentException::class.java) {
            tap(device, "expectTestTag" to "order_status", "expectUntil" to "clickable")
        }

        assertTrue(error.message!!.contains("Unknown expectUntil 'clickable'"), error.message)
        assertTrue(device.commands.isEmpty(), device.commands.toString())
    }

    @Test
    fun `over a transport that cannot cancel, a long expectation is capped and says so`() {
        val device = ScriptedScreen(listOf(screen(ORDER), screen(ORDER + STATUS)))

        val result = tap(
            device,
            "expectTestTag" to "order_status",
            "expectTimeoutMs" to 60_000,
            context = UncancellableToolContext(device.context),
        )

        assertFalse(result.isError, result.text())
        val cap = WaitForElementTool.UNCANCELLABLE_MAX_TIMEOUT_MS
        assertTrue(result.text().contains("expectTimeoutMs was capped at $cap from 60000"), result.text())
    }

    @Test
    fun `only verified and unrequested outcomes are successes`() {
        assertEquals(
            setOf(ActionOutcome.NOT_REQUESTED, ActionOutcome.VERIFIED),
            ActionOutcome.entries.filterNot { it.isError }.toSet(),
        )
    }

    private fun screen(children: String) =
        """<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="p"
            bounds="[0,0][1080,2400]">$children</node></hierarchy>"""

    private companion object {
        const val ORDER = """<node class="android.widget.Button" text="Place order" resource-id="order_button"
            package="p" clickable="true" long-clickable="true" enabled="true" bounds="[0,100][300,200]" />"""
        const val STATUS = """<node class="android.widget.TextView" text="Order placed" resource-id="order_status"
            package="p" enabled="true" bounds="[0,300][600,380]" />"""
        const val STATUS_BELOW = """<node class="android.widget.TextView" text="Order placed"
            resource-id="order_status" package="p" enabled="true" bounds="[0,1200][600,1280]" />"""
    }
}
