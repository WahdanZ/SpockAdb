package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.InputTextIntoElementTool
import spock.adb.mcp.tools.LongPressElementTool
import spock.adb.mcp.tools.TapElementTool
import java.util.concurrent.TimeUnit

class ComposeActionToolsTest {
    private class Screen(xml: String) {
        val commands = mutableListOf<String>()
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                commands += command
                val output = when {
                    command.startsWith("cat ") -> xml
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

    private fun screenXml(children: String) =
        "<hierarchy><node class=\"android.view.View\" bounds=\"[0,0][500,500]\">$children</node></hierarchy>"

    private fun control(text: String, tag: String, enabled: Boolean = true) =
        """<node class="android.widget.EditText" text="$text" resource-id="$tag" package="p"
            clickable="true" long-clickable="true" focusable="true" enabled="$enabled" bounds="[0,0][100,100]" />"""
}
