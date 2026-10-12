package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.AssertTextTool
import spock.adb.mcp.tools.FindUiElementTool
import spock.adb.mcp.tools.GetUiTreeTool
import spock.adb.mcp.tools.ScrollToElementTool
import spock.adb.mcp.tools.TapElementTool
import spock.adb.mcp.tools.UiDumpCache
import spock.adb.mcp.tools.WaitForElementTool
import spock.adb.uitree.DisplayMetrics
import spock.adb.uitree.UiFramework
import spock.adb.uitree.UiObservation
import spock.adb.uitree.UiTree
import java.util.concurrent.TimeUnit

/**
 * #182 F-14: a read right after another reuses its capture, and nothing that must see the screen
 * as it is now — a wait, an action's check, a read after input — is ever given one. And
 * `verbose: false` shortens the preamble to one line.
 */
class UiReadReuseTest {

    /** A device whose screen is the next of [dumps] at each capture, repeating the last. */
    private class ScriptedScreen(private val dumps: List<String>) {
        val commands = mutableListOf<String>()
        private var reads = 0
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                commands += command
                val output = when {
                    command.startsWith("cat ") -> dumps[minOf(reads++, dumps.lastIndex)]
                    command == "wm size" -> "Physical size: 1080x2400"
                    command == "wm density" -> "Physical density: 420"
                    else -> ""
                }.toByteArray()
                secondArg<IShellOutputReceiver>().addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device(SERIAL).copy(device = device)))
        val dumpsTaken: Int get() = commands.count { it.startsWith("uiautomator dump") }
    }

    @BeforeEach
    fun forgetCaptures() {
        // Loads the classes a find and a tap use, so loading them does not eat into the reuse window.
        val warm = ScriptedScreen(listOf(screen(ORDER)))
        FindUiElementTool().execute(selector(), warm.context)
        TapElementTool().execute(selector(), warm.context)
        UiDumpCache.shared.invalidate()
    }

    @Test
    fun `a tap right after a find reuses the find's capture, and says so`() {
        val screen = ScriptedScreen(listOf(screen(ORDER)))

        FindUiElementTool().execute(selector(), screen.context)
        val tap = TapElementTool().execute(selector(), screen.context)

        assertEquals(1, screen.dumpsTaken, screen.commands.toString())
        assertEquals(listOf("input tap 150 150"), screen.commands.filter { it.startsWith("input ") })
        assertTrue(tap.text().lineSequence().first().contains(" s capture, reused "), tap.text())
    }

    @Test
    fun `a read after a tap captures again`() {
        val screen = ScriptedScreen(listOf(screen(ORDER), screen(ORDER + STATUS)))

        TapElementTool().execute(selector(), screen.context)
        val after = FindUiElementTool().execute(selector("order_status"), screen.context)

        assertEquals(2, screen.dumpsTaken, screen.commands.toString())
        assertTrue(after.text().contains("1 match(es) for testTag='order_status'"), after.text())
        assertFalse(after.text().contains("reused"), after.text())
    }

    @Test
    fun `a wait never starts from a reused capture`() {
        val screen = ScriptedScreen(listOf(screen(ORDER), screen(ORDER + STATUS)))

        FindUiElementTool().execute(selector(), screen.context)
        val now = selector("order_status").apply { addProperty("timeoutMs", 0) }
        val wait = WaitForElementTool().execute(now, screen.context)

        assertEquals(2, screen.dumpsTaken, screen.commands.toString())
        assertFalse(wait.isError, wait.text())
        assertFalse(wait.text().contains("reused"), wait.text())
    }

    @Test
    fun `an action's check after it never reuses its capture before it`() {
        val screen = ScriptedScreen(listOf(screen(ORDER), screen(ORDER + STATUS)))

        FindUiElementTool().execute(selector(), screen.context)
        val tap = TapElementTool().execute(
            selector().apply { addProperty("expectTestTag", "order_status") },
            screen.context,
        )

        assertFalse(tap.isError, tap.text())
        assertTrue(tap.text().contains("VERIFIED"), tap.text())
        // The find's capture served the tap's target; the check took its own after the input.
        assertEquals(2, screen.dumpsTaken, screen.commands.toString())
        val input = screen.commands.indexOfFirst { it.startsWith("input ") }
        assertEquals(1, screen.commands.drop(input).count { it.startsWith("uiautomator dump") })
    }

    @Test
    fun `a scroll captures again after each swipe`() {
        val screen = ScriptedScreen(listOf(screen(LIST), screen(LIST), screen(LIST + STATUS)))

        val result = ScrollToElementTool().execute(selector("order_status"), screen.context)

        assertFalse(result.isError, result.text())
        assertEquals(2, screen.commands.count { it.startsWith("input swipe") }, screen.commands.toString())
        assertEquals(3, screen.dumpsTaken, screen.commands.toString())
    }

    @Test
    fun `verbose false is one short line in place of the capture's details and limits`() {
        val screen = ScriptedScreen(listOf(screen(ORDER)))
        val brief = JsonObject().apply { addProperty("verbose", false) }

        val tree = GetUiTreeTool().execute(brief, screen.context).text()
        val text = AssertTextTool().execute(
            JsonObject().apply {
                addProperty("text", "Place order")
                addProperty("verbose", false)
            },
            screen.context,
        ).text()

        for (answer in listOf(tree, text)) {
            val first = answer.lineSequence().first()
            assertTrue(first.startsWith("Observed on $SERIAL, window p, "), answer)
            assertFalse(answer.contains("Limits:"), answer)
            assertFalse(answer.contains("source: uiautomator"), answer)
        }
        assertTrue(GetUiTreeTool().execute(JsonObject(), screen.context).text().contains("\nLimits: "))
    }

    @Test
    fun `a capture is kept for its device, until it expires or input is sent`() {
        var now = 0L
        val cache = UiDumpCache { now }
        val device = mockk<IDevice>()
        val observation = observation()

        cache.store(SERIAL, device, observation, cache.generation())
        now += TimeUnit.MILLISECONDS.toNanos(UiDumpCache.TTL_MS - 1)
        assertEquals(UiDumpCache.TTL_MS - 1, cache.recent(SERIAL, device)?.second)
        assertNull(cache.recent(SERIAL, mockk()), "another device under the same serial: a reconnect")
        assertNull(cache.recent("emulator-5556", device))

        now += TimeUnit.MILLISECONDS.toNanos(1)
        assertNull(cache.recent(SERIAL, device), "expired")

        cache.store(SERIAL, device, observation, cache.generation())
        cache.invalidate()
        assertNull(cache.recent(SERIAL, device), "input was sent")
    }

    @Test
    fun `a capture input overtook is not kept, and input that fails still empties the cache`() {
        val cache = UiDumpCache()
        val device = mockk<IDevice>()

        val since = cache.generation()
        cache.aroundInput { }
        cache.store(SERIAL, device, observation(), since)
        assertNull(cache.recent(SERIAL, device), "captured while input went out")

        cache.store(SERIAL, device, observation(), cache.generation())
        assertNotNull(cache.recent(SERIAL, device))
        assertThrows(IllegalStateException::class.java) { cache.aroundInput { error("timed out") } }
        assertNull(cache.recent(SERIAL, device))
    }

    private fun observation() = UiObservation(
        tree = UiTree(null, UiFramework.VIEWS, UiTree.TestTagSupport.NOT_APPLICABLE),
        deviceSerial = SERIAL,
        startedAtMillis = 0,
        completedAtMillis = 0,
        metrics = DisplayMetrics.UNKNOWN,
    )

    private fun selector(tag: String = "order_button") = JsonObject().apply {
        addProperty("testTag", tag)
        addProperty("exactTag", true)
    }

    private fun screen(children: String) =
        """<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="p"
            bounds="[0,0][1080,2400]">$children</node></hierarchy>"""

    private companion object {
        const val SERIAL = "emulator-5554"
        const val ORDER = """<node class="android.widget.Button" text="Place order" resource-id="order_button"
            package="p" clickable="true" enabled="true" bounds="[0,100][300,200]" />"""
        const val STATUS = """<node class="android.widget.TextView" text="Order placed" resource-id="order_status"
            package="p" enabled="true" bounds="[0,300][600,380]" />"""
        const val LIST = """<node class="androidx.recyclerview.widget.RecyclerView" resource-id="list" package="p"
            scrollable="true" enabled="true" bounds="[0,0][1080,2400]" />"""
    }
}
