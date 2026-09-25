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
import spock.adb.mcp.tools.AssertEnabledTool
import spock.adb.mcp.tools.AssertTextTool
import spock.adb.mcp.tools.AssertVisibleTool
import spock.adb.mcp.tools.InputTextIntoElementTool
import spock.adb.mcp.tools.LongPressElementTool
import spock.adb.mcp.tools.ScrollToElementTool
import spock.adb.mcp.tools.TapElementTool
import java.util.concurrent.TimeUnit

class ComposeActionToolsTest {
    /**
     * A device showing [xml], or after each swipe the next of [after]. Without [size] it does not
     * answer `wm size`, so the viewport is the window's own bounds.
     */
    private class Screen(xml: String, private val size: String = "", after: List<String> = emptyList()) {
        private val dumps = listOf(xml) + after
        val commands = mutableListOf<String>()
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                commands += command
                val swipes = commands.count { it.startsWith("input swipe") }
                val output = when {
                    command.startsWith("cat ") -> dumps[swipes.coerceAtMost(dumps.lastIndex)]
                    command == "wm size" -> size
                    command == "wm density" -> "Physical density: 320"
                    else -> ""
                }.toByteArray()
                secondArg<IShellOutputReceiver>().addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))
    }

    @Test
    fun `ambiguous controls never send input to the device`() {
        val screen = Screen(screenXml(control("Save", "first") + control("Save", "second")))
        val error = assertThrows(IllegalArgumentException::class.java) {
            TapElementTool().execute(JsonObject().apply { addProperty("text", "Save") }, screen.context)
        }
        assertTrue(error.message!!.contains("Ambiguous"))
        assertTrue(screen.commands.none { it.startsWith("input ") })
    }

    @Test
    fun `exact tag dispatches one tap without claiming the outcome is verified`() {
        val screen = Screen(screenXml(control("Save", "save") + control("Save", "save_other")))
        val result = TapElementTool().execute(
            JsonObject().apply {
                addProperty("testTag", "save")
                addProperty("exactTag", true)
                addProperty("packageName", "p")
            },
            screen.context,
        )
        assertEquals(listOf("input tap 50 50"), screen.commands.filter { it.startsWith("input ") })
        assertTrue(result.text().contains("outcome not verified"))
    }

    @Test
    fun `disabled controls reject tap, long press and text entry before input`() {
        for (tool in listOf(TapElementTool(), LongPressElementTool(), InputTextIntoElementTool())) {
            val screen = Screen(screenXml(control("Name", "name", enabled = false)))
            val error = assertThrows(IllegalArgumentException::class.java) {
                tool.execute(
                    JsonObject().apply {
                        addProperty("testTag", "name")
                        addProperty("value", "hello")
                    },
                    screen.context,
                )
            }
            assertTrue(error.message!!.contains("disabled"), "${tool.name}: ${error.message}")
            assertTrue(screen.commands.none { it.startsWith("input ") })
        }
    }

    @Test
    fun `a target outside the viewport is refused with a pointer to scrolling, and nothing is sent`() {
        val screen = Screen(listScreen(button("far", "[0,1200][300,1300]")), size = PHONE)

        for (tool in listOf(TapElementTool(), LongPressElementTool(), InputTextIntoElementTool())) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                tool.execute(
                    JsonObject().apply {
                        addProperty("testTag", "far")
                        addProperty("value", "hello")
                    },
                    screen.context,
                )
            }
            assertTrue(error.message!!.contains("android_scroll_to_element"), "${tool.name}: ${error.message}")
            assertTrue(error.message!!.contains("outside the viewport"), "${tool.name}: ${error.message}")
        }
        assertEquals(0, screen.commands.count { it.startsWith("input ") }, screen.commands.toString())
    }

    @Test
    fun `a partly scrolled-out target is tapped at the centre of the part in view`() {
        // The list ends at y=1000; the button runs from 900 to 1100, so only [0,900][200,1000] is in view.
        val screen = Screen(listScreen(button("half", "[0,900][200,1100]")), size = PHONE)

        val result = TapElementTool().execute(JsonObject().apply { addProperty("testTag", "half") }, screen.context)

        assertEquals(listOf("input tap 100 950"), screen.commands.filter { it.startsWith("input ") })
        assertTrue(result.text().contains("centre of its part in the viewport [0,900][200,1000]"), result.text())
        assertTrue(result.text().contains("outcome not verified"), result.text())
    }

    @Test
    fun `without a viewport a tap goes to the bounds centre and says the viewport was unknown`() {
        val screen = Screen(noViewport(button("save", "[0,0][100,100]")))

        val result = TapElementTool().execute(JsonObject().apply { addProperty("testTag", "save") }, screen.context)

        assertEquals(listOf("input tap 50 50"), screen.commands.filter { it.startsWith("input ") })
        assertTrue(result.text().contains("viewport unknown"), result.text())
    }

    @Test
    fun `an off-screen duplicate still makes a selector ambiguous`() {
        val duplicates = button("dup", "[0,100][300,200]") + button("dup", "[0,1200][300,1300]")
        val screen = Screen(listScreen(duplicates), size = PHONE)

        val error = assertThrows(IllegalArgumentException::class.java) {
            TapElementTool().execute(JsonObject().apply { addProperty("testTag", "dup") }, screen.context)
        }

        assertTrue(error.message!!.contains("Ambiguous"), error.message)
        assertTrue(screen.commands.none { it.startsWith("input ") })
    }

    @Test
    fun `assert_visible fails for a node outside the viewport and passes, without claiming more, for one in it`() {
        val offScreen = AssertVisibleTool().execute(
            JsonObject().apply { addProperty("testTag", "far") },
            Screen(listScreen(button("far", "[0,1200][300,1300]")), size = PHONE).context,
        )
        assertTrue(offScreen.isError)
        assertTrue(offScreen.text().contains("FAIL"), offScreen.text())
        assertTrue(offScreen.text().contains("present in the tree but outside the viewport"), offScreen.text())

        val onScreen = AssertVisibleTool().execute(
            JsonObject().apply { addProperty("testTag", "near") },
            Screen(listScreen(button("near", "[0,100][300,200]")), size = PHONE).context,
        )
        assertFalse(onScreen.isError)
        assertTrue(onScreen.text().contains("PASS"), onScreen.text())
        assertTrue(onScreen.text().contains("occlusion not checked"), onScreen.text())
        assertTrue(onScreen.text().contains("1 of 1 match(es) in the viewport"), onScreen.text())
    }

    @Test
    fun `assert_visible is inconclusive, and an error, when the viewport is unknown`() {
        val result = AssertVisibleTool().execute(
            JsonObject().apply { addProperty("testTag", "save") },
            Screen(noViewport(button("save", "[0,0][100,100]"))).context,
        )

        assertTrue(result.isError)
        assertTrue(result.text().contains("INCONCLUSIVE"), result.text())
        assertFalse(result.text().contains("PASS"), result.text())
    }

    @Test
    fun `assert_visible fails plainly when nothing matched`() {
        val result = AssertVisibleTool().execute(
            JsonObject().apply { addProperty("testTag", "missing") },
            Screen(listScreen(button("near", "[0,100][300,200]")), size = PHONE).context,
        )

        assertTrue(result.isError)
        assertTrue(result.text().contains("FAIL: nothing matched"), result.text())
    }

    @Test
    fun `assert_enabled fails on two matches, listing both, rather than answering for the first`() {
        // The first is enabled and the second disabled: checking only the first would pass.
        val screen = Screen(screenXml(control("Save", "save_draft") + control("Save", "save_final", enabled = false)))

        val result = AssertEnabledTool().execute(JsonObject().apply { addProperty("text", "Save") }, screen.context)

        assertTrue(result.isError, result.text())
        val text = result.text()
        assertTrue(text.startsWith("Observed on emulator-5554"), text)
        assertTrue(text.contains("FAIL: Ambiguous selector text='Save': 2 matches"), text)
        assertTrue(text.contains("id='save_draft'") && text.contains("id='save_final'"), text)
        assertFalse(text.contains("PASS"), text)
    }

    @Test
    fun `assert_enabled still passes one enabled match and fails one disabled match`() {
        val enabled = AssertEnabledTool().execute(
            JsonObject().apply { addProperty("testTag", "near") },
            Screen(listScreen(button("near", "[0,100][300,200]")), size = PHONE).context,
        )
        assertFalse(enabled.isError, enabled.text())
        assertTrue(enabled.text().contains("PASS: 'Label' is enabled"), enabled.text())

        val disabled = AssertEnabledTool().execute(
            JsonObject().apply { addProperty("testTag", "name") },
            Screen(screenXml(control("Name", "name", enabled = false))).context,
        )
        assertTrue(disabled.isError, disabled.text())
        assertTrue(disabled.text().contains("present but disabled"), disabled.text())
    }

    @Test
    fun `assert_text follows the same rules as assert_visible`() {
        fun assertText(xml: String, size: String = PHONE) = AssertTextTool().execute(
            JsonObject().apply { addProperty("text", "Label") },
            Screen(xml, size = size).context,
        )

        val offScreen = assertText(listScreen(button("far", "[0,1200][300,1300]")))
        assertTrue(offScreen.isError && offScreen.text().contains("outside the viewport"), offScreen.text())

        val onScreen = assertText(listScreen(button("near", "[0,100][300,200]")))
        assertTrue(!onScreen.isError && onScreen.text().contains("occlusion not checked"), onScreen.text())

        val unknown = assertText(noViewport(button("save", "[0,0][100,100]")), size = "")
        assertTrue(unknown.isError && unknown.text().contains("INCONCLUSIVE"), unknown.text())
    }

    @Test
    fun `scrolling does not stop at a match that is only in the tree`() {
        // First capture: the target is laid out below the list. After one swipe it is inside it.
        val screen = Screen(
            listScreen(button("target", "[0,1200][300,1300]")),
            size = PHONE,
            after = listOf(listScreen(button("target", "[0,500][300,600]"))),
        )

        val result = ScrollToElementTool().execute(byTag("target"), screen.context)

        assertFalse(result.isError, result.text())
        assertEquals(1, screen.commands.count { it.startsWith("input swipe") }, screen.commands.toString())
        assertTrue(result.text().contains("after 1 scroll(s)"), result.text())
        assertTrue(result.text().contains("within the viewport"), result.text())
    }

    @Test
    fun `without a viewport scrolling keeps the old presence rule and says so`() {
        val screen = Screen(noViewport(button("save", "[0,0][100,100]")))

        val result = ScrollToElementTool().execute(byTag("save"), screen.context)

        assertFalse(result.isError, result.text())
        assertEquals(0, screen.commands.count { it.startsWith("input swipe") })
        assertTrue(result.text().contains("viewport is unknown"), result.text())
    }

    private fun byTag(tag: String) = JsonObject().apply { addProperty("testTag", tag) }

    private fun screenXml(children: String) =
        "<hierarchy><node class=\"android.view.View\" bounds=\"[0,0][500,500]\">$children</node></hierarchy>"

    /** A 1080x2400 window holding a list that scrolls within [0,0][1080,1000], with [children] inside it. */
    private fun listScreen(children: String) = """
        <hierarchy rotation="0">
          <node class="android.widget.FrameLayout" package="p" bounds="[0,0][1080,2400]">
            <node class="android.view.View" resource-id="list" package="p" scrollable="true" enabled="true"
                  bounds="[0,0][1080,1000]">$children</node>
          </node>
        </hierarchy>
    """.trimIndent()

    /** A window with no area and no display size: nothing to call a viewport. */
    private fun noViewport(children: String) =
        "<hierarchy rotation=\"0\"><node class=\"android.view.View\" package=\"p\" bounds=\"[0,0][0,0]\">" +
            "$children</node></hierarchy>"

    private fun button(tag: String, bounds: String) =
        """<node class="android.widget.EditText" text="Label" resource-id="$tag" package="p" clickable="true"
            long-clickable="true" focusable="true" enabled="true" bounds="$bounds" />"""

    private fun control(text: String, tag: String, enabled: Boolean = true) =
        """<node class="android.widget.EditText" text="$text" resource-id="$tag" package="p"
            clickable="true" long-clickable="true" focusable="true" enabled="$enabled" bounds="[0,0][100,100]" />"""

    private companion object {
        const val PHONE = "Physical size: 1080x2400"
    }
}
