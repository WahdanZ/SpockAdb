package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** `android_take_screenshot` — the screen, as MCP image content. */
class TakeScreenshotTool : AdbTool {
    override val name = "android_take_screenshot"
    override val description =
        "Capture the device screen and return it as an image. Use this to see what is " +
            "actually on screen rather than inferring it from logs, and to verify the " +
            "result of an action you just performed."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))

        val raw = device.getScreenshot(SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: return ToolResult.error("The device returned no screenshot.")

        val image = raw.asBufferedImage()
            ?: return ToolResult.error("The screenshot could not be decoded.")

        val png = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
        return ToolResult.image(Base64.getEncoder().encodeToString(png))
    }

    private companion object {
        const val SCREENSHOT_TIMEOUT_SECONDS = 15L
    }
}

/** `android_open_deep_link` — semantic alternative to `am start`. */
class OpenDeepLinkTool : AdbTool {
    override val name = "android_open_deep_link"
    override val description =
        "Open a URI on the device with an ACTION_VIEW intent, the way tapping a link would. " +
            "Optionally restrict it to one package so you test your own app's handler rather " +
            "than whatever else claims the scheme. Follow with android_get_current_activity " +
            "to see which screen actually opened."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("uri", "The URI to open, e.g. myapp://product/42.", required = true)
        string("packageName", "Restrict the intent to this package.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val uri = arguments.requiredString("uri")
        val target = arguments.optionalString("packageName")

        val command = buildString {
            append("am start -a android.intent.action.VIEW -d ").append(ShellQuote.quote(uri))
            target?.let { append(" -p ").append(ShellQuote.quote(it)) }
        }

        val output = McpShell.run(device, command)
        return if (output.contains("Error", ignoreCase = true)) {
            ToolResult.error("Could not open $uri:\n$output")
        } else {
            ToolResult.text("Opened $uri.\n$output")
        }
    }
}

/** `android_input_text` — type into the focused field. */
class InputTextTool : AdbTool {
    override val name = "android_input_text"
    override val description =
        "Type into whatever field currently has focus. Prefer " +
            "android_input_text_into_element, which focuses the right field first."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("text", "The text to type.", required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val text = arguments.requiredString("text")
        McpShell.run(device, "input text ${ShellQuote.quote(text)}")
        return ToolResult.text("Typed ${text.length} characters into the focused field.")
    }
}

/** `android_tap`, `android_swipe`, `android_press_key`. */
class TapTool : AdbTool {
    override val name = "android_tap"
    override val description =
        "Tap raw screen coordinates. This is the FALLBACK: prefer android_tap_element, which " +
            "resolves the element from semantics and works on Compose screens where guessed " +
            "coordinates are the main cause of flaky automation. Use this only when no " +
            "semantic identifier is available."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer("x", "X coordinate in pixels.", required = true)
        integer("y", "Y coordinate in pixels.", required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val x = arguments.requiredInt("x")
        val y = arguments.requiredInt("y")
        McpShell.run(device, "input tap $x $y")
        return ToolResult.text("Tapped ($x, $y).")
    }
}

class SwipeTool : AdbTool {
    override val name = "android_swipe"
    override val description =
        "Swipe between two points. Use it to scroll, or with a long duration to long-press."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer("startX", "Start X in pixels.", required = true)
        integer("startY", "Start Y in pixels.", required = true)
        integer("endX", "End X in pixels.", required = true)
        integer("endY", "End Y in pixels.", required = true)
        integer("durationMs", "Duration in milliseconds. Defaults to 300. Use 1000+ to long-press.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val duration = arguments.optionalInt("durationMs", DEFAULT_SWIPE_MS)
        McpShell.run(
            device,
            "input swipe ${arguments.requiredInt("startX")} ${arguments.requiredInt("startY")} " +
                "${arguments.requiredInt("endX")} ${arguments.requiredInt("endY")} $duration",
        )
        return ToolResult.text("Swiped.")
    }

    private companion object {
        const val DEFAULT_SWIPE_MS = 300
    }
}

class PressKeyTool : AdbTool {
    override val name = "android_press_key"
    override val description =
        "Press a hardware or navigation key: BACK, HOME, ENTER, TAB, DELETE, MENU, " +
            "APP_SWITCH, VOLUME_UP, VOLUME_DOWN or POWER."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        enumeration("key", "The key to press.", KEYCODES.keys.toList(), required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val key = arguments.requiredString("key").uppercase()
        val code = KEYCODES[key]
            ?: return ToolResult.error("Unsupported key '$key'. Supported: ${KEYCODES.keys.joinToString()}.")

        McpShell.run(device, "input keyevent $code")
        return ToolResult.text("Pressed $key.")
    }

    companion object {
        val KEYCODES = linkedMapOf(
            "BACK" to 4,
            "HOME" to 3,
            "APP_SWITCH" to 187,
            "MENU" to 82,
            "ENTER" to 66,
            "TAB" to 61,
            "DELETE" to 67,
            "VOLUME_UP" to 24,
            "VOLUME_DOWN" to 25,
            "POWER" to 26,
        )
    }
}

private fun JsonObject.requiredInt(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull }?.asInt
        ?: throw IllegalArgumentException("Missing required argument '$name'")
