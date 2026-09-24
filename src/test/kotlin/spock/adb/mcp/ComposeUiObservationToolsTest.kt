package spock.adb.mcp

import com.android.ddmlib.AdbCommandRejectedException
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
import spock.adb.mcp.tools.AccessibilityAuditTool
import spock.adb.mcp.tools.AdbTool
import spock.adb.mcp.tools.AssertEnabledTool
import spock.adb.mcp.tools.AssertTextTool
import spock.adb.mcp.tools.AssertVisibleTool
import spock.adb.mcp.tools.FindUiElementTool
import spock.adb.mcp.tools.GetUiTreeTool
import spock.adb.mcp.tools.ScrollToElementTool
import spock.adb.mcp.tools.TapElementTool
import spock.adb.uitree.UiCaptureException
import java.util.concurrent.TimeUnit

/**
 * Every tool built on a screen capture says which capture it was, in the same words, before
 * anything it concludes from it — and a lost device is told what an agent can do about it.
 */
class ComposeUiObservationToolsTest {

    private class Screen(
        xml: String = SCREEN,
        density: String = "Physical density: $DENSITY",
        private val failure: Exception? = null,
    ) {
        val commands = mutableListOf<String>()
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                failure?.let { throw it }
                val command = firstArg<String>()
                commands += command
                val output = when {
                    command.startsWith("cat ") -> xml
                    command == "wm size" -> "Physical size: 1080x2400"
                    command == "wm density" -> density
                    else -> ""
                }.toByteArray()
                secondArg<IShellOutputReceiver>().addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device(SERIAL).copy(device = device)))

        fun run(tool: AdbTool, arguments: JsonObject = JsonObject()) = tool.execute(arguments, context).text()
    }

    private fun byTag(tag: String) = JsonObject().apply { addProperty("testTag", tag) }

    @Test
    fun `every capture-based result starts with the same summary line`() {
        val screen = Screen()
        val results = mapOf(
            "android_get_ui_tree" to screen.run(GetUiTreeTool()),
            "android_find_ui_element" to screen.run(FindUiElementTool(), byTag("save")),
            "android_assert_visible" to screen.run(AssertVisibleTool(), byTag("save")),
            "android_assert_enabled" to screen.run(AssertEnabledTool(), byTag("save")),
            "android_assert_text" to screen.run(AssertTextTool(), JsonObject().apply { addProperty("text", "Save") }),
            "android_tap_element" to screen.run(TapElementTool(), byTag("save")),
            "android_accessibility_audit" to screen.run(AccessibilityAuditTool()),
        )

        results.forEach { (tool, text) ->
            val first = text.lineSequence().first()
            assertTrue(first.startsWith("Observed on $SERIAL, window $PACKAGE, "), "$tool: $first")
            assertTrue(first.contains("viewport 1080x2400 rot 0, $DENSITY dpi"), "$tool: $first")
        }
    }

    @Test
    fun `results that make a claim about the screen state its limits too`() {
        val screen = Screen()

        listOf(
            screen.run(GetUiTreeTool()),
            screen.run(FindUiElementTool(), byTag("save")),
            screen.run(AssertVisibleTool(), byTag("save")),
            screen.run(AccessibilityAuditTool()),
        ).forEach { text ->
            val second = text.lines()[1]
            assertTrue(second.startsWith("Limits: "), text)
            assertTrue(second.contains("covered by another"), "a present node is not claimed unobscured: $second")
        }
    }

    @Test
    fun `a refusal says which capture it was decided against`() {
        val screen = Screen()

        val error = assertThrows(IllegalStateException::class.java) {
            screen.run(TapElementTool(), byTag("missing"))
        }

        assertTrue(error.message!!.startsWith("Observed on $SERIAL"), error.message)
        assertTrue(error.message!!.contains("No element matched"), error.message)
    }

    @Test
    fun `the audit estimates touch targets at the density the observation read`() {
        val text = Screen(density = "Physical density: 480\nOverride density: 320").run(AccessibilityAuditTool())

        assertTrue(text.lineSequence().first().contains("320 dpi"), text)
        assertTrue(text.contains("Touch-target estimates use 320dpi"), text)
        // 60px at 320dpi is 30dp: the small control is a finding at the density that was read.
        assertTrue(text.contains("60x60px at 320dpi"), text)
    }

    @Test
    fun `the audit says so when the density could not be read`() {
        val text = Screen(density = "Permission denied").run(AccessibilityAuditTool())

        assertTrue(text.lineSequence().first().contains("density unknown"), text)
        assertTrue(text.contains("display density could not be read"), text)
        assertFalse(text.contains("60x60px at"), "no dp estimate without a density: $text")
    }

    @Test
    fun `scrolling measures the display once, not once per swipe`() {
        val screen = Screen(xml = SCROLLING)

        val result = ScrollToElementTool().execute(
            byTag("missing").apply { addProperty("maxSwipes", 3) },
            screen.context,
        )

        assertTrue(result.isError)
        assertEquals(3, screen.commands.count { it.startsWith("uiautomator dump") })
        assertEquals(1, screen.commands.count { it == "wm size" }, screen.commands.toString())
        assertEquals(1, screen.commands.count { it == "wm density" }, screen.commands.toString())
    }

    @Test
    fun `a lost device points an agent at android_list_devices`() {
        val screen = Screen(failure = AdbCommandRejectedException("device offline"))

        val error = assertThrows(UiCaptureException::class.java) { screen.run(GetUiTreeTool()) }

        assertEquals(UiCaptureException.Kind.DEVICE_UNAVAILABLE, error.kind)
        assertTrue(error.message!!.contains(SERIAL), error.message)
        assertTrue(error.message!!.contains("android_list_devices"), error.message)
    }

    private companion object {
        const val SERIAL = "emulator-5554"
        const val PACKAGE = "p"
        const val DENSITY = 420

        val SCREEN = """
            <hierarchy rotation="0">
              <node class="android.widget.FrameLayout" package="p" bounds="[0,0][1080,2400]">
                <node class="androidx.compose.ui.platform.AndroidComposeView" package="p" bounds="[0,0][1080,2400]">
                  <node class="android.widget.Button" text="Save" resource-id="save" package="p"
                        clickable="true" enabled="true" bounds="[0,0][300,200]" />
                  <node class="android.view.View" content-desc="Close" resource-id="close" package="p"
                        clickable="true" enabled="true" bounds="[400,0][460,60]" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        val SCROLLING = """
            <hierarchy rotation="0">
              <node class="android.widget.FrameLayout" package="p" bounds="[0,0][1080,2400]">
                <node class="android.widget.ScrollView" resource-id="list" package="p"
                      scrollable="true" enabled="true" bounds="[0,0][1080,2400]">
                  <node class="android.widget.TextView" text="Row 1" package="p" bounds="[0,0][1080,200]" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()
    }
}
