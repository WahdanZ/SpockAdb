package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.command.PushMessage
import spock.adb.command.sendPushMessage

/**
 * `android_send_push_message` — hand a push message to the app's messaging receiver over ADB.
 *
 * SAFE_ACTION: it changes nothing persistent on the device. It is the same broadcast Google Play
 * services sends, which the app handles as it would any push. What it gives an agent is the loop
 * trigger → wait → assert against logcat or the UI tree, with no server and no token.
 *
 * Refused and undelivered sends are errors, not text: an agent that reads "completed" as
 * "delivered" goes on to assert against a screen the message never reached.
 */
class SendPushMessageTool : AdbTool {
    override val name = "android_send_push_message"
    override val title = "Send push message"
    override val description =
        "Send an FCM push message to the app over ADB, no server or token needed. Needs a debuggable " +
            "build or root. Then check android_get_logcat or the UI."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        stringMap("data", "Data payload of string values, e.g. {\"orderId\":\"42\"}.")
        string("title", "Notification title; a title or body makes it a notification message.")
        string("body", "Notification body.")
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val message = try {
            PushMessage(
                data = arguments.stringMap("data"),
                title = arguments.optionalString("title"),
                body = arguments.optionalString("body"),
            ).also { it.requireSendable() }
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "The push message is not valid.")
        }
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return context.withPackage(arguments) { packageName ->
            val delivery = device.sendPushMessage(packageName, message)
            val text = listOfNotNull(
                delivery.message,
                "Route: ${delivery.access.label}.",
                delivery.detail.takeIf { it.isNotBlank() },
            ).joinToString("\n")
            if (delivery.accepted) ToolResult.text(text) else ToolResult.error(text)
        }
    }

    /** Every value is sent as a string extra, so a number or boolean is taken as its text. */
    private fun JsonObject.stringMap(name: String): Map<String, String> {
        val node = get(name)?.takeIf { !it.isJsonNull } ?: return emptyMap()
        require(node.isJsonObject) { "'$name' must be an object of string keys and values." }
        return node.asJsonObject.entrySet().associate { (key, value) ->
            require(value.isJsonPrimitive) { "'$name.$key' must be a string, not $value." }
            key to value.asString
        }
    }
}
