package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.CancellableToolContext
import spock.adb.mcp.tools.ToolResult
import spock.adb.mcp.tools.WaitForElementTool
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class ComposeWaitToolsTest {

    /**
     * A device whose screen is the next of [dumps] at each `cat`, repeating the last. With [hang]
     * a dump never finishes until ddmlib would stop reading, which is when the receiver says it is
     * cancelled — how the real bridge behaves.
     */
    private class ScriptedDevice(private val dumps: List<String>, private val hang: Boolean = false) {
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val dumping = CountDownLatch(1)
        private var reads = 0
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                val receiver = secondArg<IShellOutputReceiver>()
                commands += command
                if (hang && command.startsWith("uiautomator")) {
                    dumping.countDown()
                    while (!receiver.isCancelled) LockSupport.parkNanos(POLL_NANOS)
                }
                val output = when {
                    command.startsWith("cat ") -> dumps[minOf(reads++, dumps.lastIndex)]
                    command == "wm size" -> "Physical size: 1080x2400"
                    command == "wm density" -> "Physical density: 420"
                    else -> ""
                }.toByteArray()
                receiver.addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is Number -> addProperty(key, value)
                else -> addProperty(key, value.toString())
            }
        }
    }

    @Test
    fun `an element that appears on the third look passes, saying how many looks it took`() {
        val device = ScriptedDevice(listOf(screen(), screen(), screen(button("wait_appears"))))

        val result = WaitForElementTool().execute(
            args("testTag" to "wait_appears", "timeoutMs" to 5_000, "pollIntervalMs" to 100),
            device.context,
        )

        assertFalse(result.isError, result.text())
        assertTrue(result.text().startsWith("Observed on emulator-5554"), result.text())
        assertTrue(
            result.text().contains("PASS: testTag='wait_appears' is visible after 3 observation(s)"),
            result.text(),
        )
        // The display is measured once, not once per look.
        assertEquals(1, device.commands.count { it == "wm size" }, device.commands.toString())
        assertTrue(device.commands.none { it.startsWith("input ") }, device.commands.toString())
    }

    @Test
    fun `a wait that runs out fails with the last reason`() {
        val device = ScriptedDevice(listOf(screen()))

        val result = WaitForElementTool().execute(
            args("testTag" to "wait_appears", "timeoutMs" to 0),
            device.context,
        )

        assertTrue(result.isError)
        assertTrue(
            result.text().contains("FAIL: timed out waiting for testTag='wait_appears' to be visible"),
            result.text(),
        )
        assertTrue(result.text().contains("Last: nothing matched testTag='wait_appears'"), result.text())
    }

    @Test
    fun `a state wait passes when the one match reaches it`() {
        val device = ScriptedDevice(
            listOf(screen(button("wait_enable_target", enabled = false)), screen(button("wait_enable_target"))),
        )

        val result = WaitForElementTool().execute(
            args("testTag" to "wait_enable_target", "until" to "enabled", "pollIntervalMs" to 100),
            device.context,
        )

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("is enabled after 2 observation(s)"), result.text())
    }

    @Test
    fun `an interrupted wait ends within two seconds as cancelled, having sent no input`() {
        val device = ScriptedDevice(listOf(screen()), hang = true)
        val result = AtomicReference<ToolResult>()
        val worker = Thread {
            val arguments = args("testTag" to "wait_appears", "timeoutMs" to 60_000)
            result.set(WaitForElementTool().execute(arguments, device.context))
        }

        worker.start()
        assertTrue(device.dumping.await(2, TimeUnit.SECONDS), "the dump never started")
        worker.interrupt()
        worker.join(2_000)

        assertFalse(worker.isAlive, "the wait did not stop when its thread was interrupted")
        assertTrue(result.get().isError)
        assertTrue(result.get().text().startsWith("CANCELLED"), result.get().text())
        assertTrue(result.get().text().contains("nothing was changed on the device"), result.get().text())
        assertTrue(device.commands.none { it.startsWith("input ") }, device.commands.toString())
    }

    @Test
    fun `the assistant's stop flag cancels a wait without an interrupt`() {
        val device = ScriptedDevice(listOf(screen()), hang = true)
        val stopped = AtomicBoolean(false)
        val context = CancellableToolContext(device.context, stopped::get)
        val result = AtomicReference<ToolResult>()
        val worker = Thread {
            result.set(WaitForElementTool().execute(args("testTag" to "wait_appears", "timeoutMs" to 60_000), context))
        }

        worker.start()
        assertTrue(device.dumping.await(2, TimeUnit.SECONDS), "the dump never started")
        stopped.set(true)
        worker.join(2_000)

        assertFalse(worker.isAlive, "the wait did not see the stop flag")
        assertTrue(result.get().text().startsWith("CANCELLED"), result.get().text())
        assertFalse(worker.isInterrupted)
    }

    @Test
    fun `a lost device fails pointing to android_list_devices`() {
        val device = mockk<IDevice>(relaxed = true).also {
            every {
                it.executeShellCommand(any(), any(), any(), any<TimeUnit>())
            } throws java.io.IOException("device offline")
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))

        val result = WaitForElementTool().execute(args("testTag" to "wait_appears"), context)

        assertTrue(result.isError)
        assertTrue(result.text().startsWith("FAIL: device emulator-5554 became unavailable"), result.text())
        assertTrue(result.text().contains("android_list_devices"), result.text())
    }

    @Test
    fun `a last capture out of time reads as the wait timing out, keeping what was seen before it`() {
        var dumps = 0
        val device = mockk<IDevice>(relaxed = true).also {
            every { it.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                if (command.startsWith("uiautomator") && ++dumps == 2) {
                    throw com.android.ddmlib.TimeoutException("timed out")
                }
                val output = (if (command.startsWith("cat ")) screen() else "").toByteArray()
                secondArg<IShellOutputReceiver>().addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))

        val result = WaitForElementTool().execute(args("testTag" to "wait_appears", "pollIntervalMs" to 100), context)

        assertTrue(result.isError)
        val text = result.text()
        assertTrue(
            text.contains("FAIL: timed out waiting for testTag='wait_appears' to be visible; 2 observation(s)"),
            text,
        )
        assertTrue(text.contains("did not finish within"), text)
        assertTrue(text.contains("Before it: nothing matched testTag='wait_appears'"), text)
    }

    @Test
    fun `an unknown until is an argument error, before the device is touched`() {
        val device = ScriptedDevice(listOf(screen()))

        val error = assertThrows(IllegalArgumentException::class.java) {
            WaitForElementTool().execute(args("testTag" to "wait_appears", "until" to "clickable"), device.context)
        }

        assertTrue(error.message!!.contains("Unknown until 'clickable'"), error.message)
        assertTrue(device.commands.isEmpty(), device.commands.toString())
    }

    @Test
    fun `gone passes at once for an element that was never there`() {
        val device = ScriptedDevice(listOf(screen()))

        val arguments = args("testTag" to "wait_disappears", "until" to "gone")
        val result = WaitForElementTool().execute(arguments, device.context)

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("is gone after 1 observation(s)"), result.text())
    }

    private fun button(tag: String, enabled: Boolean = true) =
        """<node class="android.widget.Button" resource-id="$tag" package="p" clickable="true" enabled="$enabled"
            bounds="[0,100][300,200]" />"""

    private fun screen(children: String = "") =
        """<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="p"
            bounds="[0,0][1080,2400]">$children</node></hierarchy>"""

    private companion object {
        const val POLL_NANOS = 5_000_000L
    }
}
