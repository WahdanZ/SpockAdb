package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.command.openDeepLinkWithAmStart
import spock.adb.device.ops.ScreenshotOperations
import spock.adb.mcp.tools.RouteExpectation.Companion.routeOption
import java.util.Base64

/** `android_take_screenshot` — the screen, as MCP image content. */
class TakeScreenshotTool : AdbTool {
    override val name = "android_take_screenshot"
    override val description =
        "Capture the device screen and return it as an image. Use this to see what is " +
            "actually on screen rather than inferring it from logs, and to verify the " +
            "result of an action you just performed. By default it is a full-size PNG, about " +
            "1.2 MB of base64 for a 1080x2400 screen; when you only need to see the layout, pass " +
            "scale 0.5 and format jpeg for a far smaller image."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        number(
            "scale",
            "Shrink the image by this factor, ${ScreenshotEncoding.MIN_SCALE}-${ScreenshotEncoding.MAX_SCALE}. " +
                "Defaults to 1.0 (full size). A scaled result says how to turn its coordinates back " +
                "into screen pixels for android_tap.",
        )
        enumeration(
            "format",
            "Image format. Defaults to png; jpeg is several times smaller and fine for reading a screen.",
            ScreenshotEncoding.Format.entries.map { it.argument },
        )
        deviceSerial()
    }

    // The capture itself — and why it goes through the shell — is in ScreenshotOperations,
    // which the Diagnose tab shares.
    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val request = ScreenshotEncoding.request(arguments).getOrElse { return ToolResult.error(it.message.orEmpty()) }
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))

        val png = try {
            ScreenshotOperations(device).capture()
        } catch (e: IllegalStateException) {
            return ToolResult.error(e.message ?: "The screenshot could not be captured.")
        }
        return when {
            request.isPassThrough -> ToolResult.image(Base64.getEncoder().encodeToString(png))
            else -> encoded(png, request)
        }
    }

    private fun encoded(png: ByteArray, request: ScreenshotEncoding.Request): ToolResult {
        val encoded = try {
            ScreenshotEncoding.encode(png, request.scale, request.format)
        } catch (e: IllegalStateException) {
            return ToolResult.error(e.message ?: "The screenshot could not be re-encoded.")
        }
        val image = ToolResult.image(Base64.getEncoder().encodeToString(encoded.bytes), request.format.mimeType)
        return when {
            request.scale < ScreenshotEncoding.MAX_SCALE -> image.copy(
                content = image.content + ToolContent.Text(ScreenshotEncoding.scaleNote(request.scale, encoded)),
            )
            else -> image
        }
    }
}

/** `android_open_deep_link` — semantic alternative to `am start`. */
class OpenDeepLinkTool internal constructor(private val routes: RouteReader) : AdbTool {
    constructor() : this(SessionRouteReader)

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
        routeOption()
        integer(
            RouteExpectation.TIMEOUT,
            "How long to look for $ROUTE, in milliseconds, 0 to ${RouteExpectation.MAX_TIMEOUT_MS}. Defaults to 5000.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val signal = context.cancellationSignal()
        val route = RouteExpectation.from(arguments, context.canCancel)
        val connected = context.requireDevice(arguments.optionalString("deviceSerial"))
        val device = connected.device
        val uri = arguments.requiredString("uri")
        val target = arguments.optionalString("packageName")
        val routeCheck = route?.let {
            RouteCheck(it, { check -> routes.read(context, connected.serialNumber, target, check) }, signal)
        }
        val routeBefore = routeCheck?.before()

        // Shares the panel's implementation, so the verdict — and the silence a slow cold
        // start produces under `am -W` — cannot differ between an agent and a developer.
        // Classified by AmStartResult rather than by looking for the word "Error": a
        // Permission Denial trace does not contain it, so a refused link was reported as opened.
        val result = UiDumpCache.shared.aroundInput { device.openDeepLinkWithAmStart(uri, target) }
        val text = with(McpShell) {
            listOfNotNull(result.message, result.raw.takeIf { it.isNotBlank() })
                .joinToString("\n\n")
                .truncateForAgent(McpShell.DEFAULT_MAX_CHARS)
        }

        if (!result.succeeded) return ToolResult.error(text)
        if (route == null || routeCheck == null || routeBefore == null) return ToolResult.text(text)
        return ElementActions.routeResult(text + "\n", route, routeCheck.verify(routeBefore, "the deep link"))
    }

    private companion object {
        const val ROUTE = RouteExpectation.ROUTE
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
