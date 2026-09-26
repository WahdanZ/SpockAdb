package spock.adb.command

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Reads a push message from the JSON a developer already has: a payload from the backend, the
 * FCM HTTP v1 request body, or the legacy API's body.
 *
 * Accepted shapes, most specific first:
 * - `{"message": {...}}` — HTTP v1; the inner object is read as below
 * - `{"notification": {"title", "body"}, "data": {...}}` — v1 message or legacy body, with
 *   `android.notification` as the fallback for title and body
 * - any other object — every top-level pair is data, which is what a backend's data payload is
 *
 * FCM data values are strings. A number or boolean is taken as its text, and an object or array
 * as its JSON, which is how an app that nests JSON in a data value receives it.
 */
object PushMessageJson {

    /** @throws IllegalArgumentException naming what is wrong, for a dialog to show as is. */
    fun parse(text: String): PushMessage {
        val root = try {
            JsonParser.parseString(text.trim())
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("That is not valid JSON: ${e.cause?.message ?: e.message}", e)
        }
        require(root.isJsonObject) { "Paste a JSON object, starting with {." }
        val message = root.asJsonObject.objectOrNull("message") ?: root.asJsonObject

        val notification = message.objectOrNull("notification")
        val androidNotification = message.objectOrNull("android")?.objectOrNull("notification")
        val data = message.objectOrNull("data")
        val isEnvelope = notification != null || data != null || androidNotification != null

        val pairs = if (isEnvelope) data?.toStrings().orEmpty() else message.toStrings()
        return PushMessage(
            data = pairs,
            title = notification?.stringOrNull("title") ?: androidNotification?.stringOrNull("title"),
            body = notification?.stringOrNull("body") ?: androidNotification?.stringOrNull("body"),
        ).also {
            require(it.data.isNotEmpty() || it.isNotification) {
                "The JSON has no title, body or data to send."
            }
        }
    }

    /**
     * [message] in the shape [parse] reads first, so editing it as JSON and applying it gives the
     * same message back. Data values stay strings: a value holding JSON is shown as the string
     * the app receives, not unpacked into an object it never sees.
     */
    fun format(message: PushMessage): String {
        val root = JsonObject()
        if (message.isNotification) {
            root.add(
                "notification",
                JsonObject().apply {
                    message.title?.let { addProperty("title", it) }
                    message.body?.let { addProperty("body", it) }
                },
            )
        }
        root.add("data", JsonObject().apply { message.data.forEach { (key, value) -> addProperty(key, value) } })
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.toStrings(): Map<String, String> =
        entrySet().filterNot { it.value.isJsonNull }.associateTo(linkedMapOf()) { (key, value) -> key to value.text() }

    private fun JsonElement.text(): String = if (isJsonPrimitive) asString else toString()
}
