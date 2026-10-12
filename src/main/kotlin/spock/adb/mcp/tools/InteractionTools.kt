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
    override val title = "Screenshot"
    override val description =
        "Capture the screen as an image. Full-size PNG is about 1.2 MB of base64; scale 0.5 and " +
            "format jpeg are far smaller."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        number(
            "scale",
            "Shrink factor. Default 1.0. The result says how to map coordinates back.",
            ScreenshotEncoding.SCALES,
        )
        enumeration(
            "format",
            "Default png; jpeg is several times smaller.",
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
    override val title = "Open deep link"
    override val description =
        "Open a URI with an ACTION_VIEW intent, as tapping a link would. Follow with " +
            "android_get_current_activity to see which screen opened."
    override val safety = ToolSafety.SAFE_ACTION

    /** A web URI opens whatever site it names, in whichever app claims it. */
    override val openWorld = true
    override val inputSchema: JsonObject = Schema.obj {
        string("uri", "The URI to open, e.g. myapp://product/42.", required = true)
        string("packageName", "Only this app's handler, not whatever else claims the scheme.")
        routeOption()
        integer(
            RouteExpectation.TIMEOUT,
            "How long to look for $ROUTE, in ms. Defaults to 5000.",
            0..RouteExpectation.MAX_TIMEOUT_MS,
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
    override val title = "Type text"
    override val description =
        "Type into the focused field. Prefer android_input_text_into_element, which focuses it first."
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
    override val title = "Tap coordinates"
    override val description =
        "Tap raw screen coordinates. FALLBACK only: prefer android_tap_element, as guessed coordinates " +
            "are the main cause of flaky automation."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer("x", "X coordinate in pixels.", COORDINATES, required = true)
        integer("y", "Y coordinate in pixels.", COORDINATES, required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val x = arguments.requiredInt("x", COORDINATES)
        val y = arguments.requiredInt("y", COORDINATES)
        McpShell.run(device, "input tap $x $y")
        return ToolResult.text("Tapped ($x, $y).")
    }
}

class SwipeTool : AdbTool {
    override val name = "android_swipe"
    override val title = "Swipe"
    override val description =
        "Swipe between two points. Use it to scroll, or with a long duration to long-press."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer("startX", "Start X in pixels.", COORDINATES, required = true)
        integer("startY", "Start Y in pixels.", COORDINATES, required = true)
        integer("endX", "End X in pixels.", COORDINATES, required = true)
        integer("endY", "End Y in pixels.", COORDINATES, required = true)
        integer("durationMs", "Duration in milliseconds. Defaults to $DEFAULT_SWIPE_MS; 1000+ long-presses.", DURATIONS)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        // Every argument is read before anything is sent, so a bad one refuses the whole swipe.
        val points = listOf("startX", "startY", "endX", "endY").map { arguments.requiredInt(it, COORDINATES) }
        val duration = arguments.optionalInt("durationMs", DEFAULT_SWIPE_MS, DURATIONS)
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        McpShell.run(device, "input swipe ${points.joinToString(" ")} $duration")
        return ToolResult.text("Swiped.")
    }

    private companion object {
        const val DEFAULT_SWIPE_MS = 300
        const val MAX_SWIPE_MS = 10_000
        val DURATIONS = 1..MAX_SWIPE_MS
    }
}

private const val MAX_COORDINATE = 16_384

/**
 * Screen coordinates the raw input tools accept: never negative, and no larger than an 8K display
 * with room to spare, so a value that cannot be on any screen is refused rather than sent.
 */
private val COORDINATES = 0..MAX_COORDINATE

class PressKeyTool : AdbTool {
    override val name = "android_press_key"
    override val title = "Press key"
    override val description =
        "Press a hardware or navigation key, such as BACK or HOME."
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
