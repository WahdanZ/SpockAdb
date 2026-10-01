package spock.adb.flutter.vmservice

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.util.Base64

/**
 * Takes VM Service auth codes out of text and events before they are kept, logged or shown.
 *
 * A VM Service address is `http://127.0.0.1:<port>/<token>=/`, and whoever holds the token can
 * run code in the app [FR9]. The app itself hands it out: Flutter reports
 * `ext.flutter.connectedVmServiceUri` and `ext.flutter.activeDevToolsServerAddress` in
 * `Flutter.ServiceExtensionStateChanged` events, and the engine prints the address to stdout.
 */
object Redaction {

    const val PLACEHOLDER = "<redacted>"

    /** The two extensions whose state-changed `value` is an address. */
    val ADDRESS_EXTENSIONS = setOf(
        "ext.flutter.connectedVmServiceUri",
        "ext.flutter.activeDevToolsServerAddress",
    )

    private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"

    /** `scheme://host:port/<token>=` — the path segment right after the authority. */
    private val TOKEN_SEGMENT = Regex("""(://[^/\s"'<>]+/)[A-Za-z0-9_\-]+=+(?![A-Za-z0-9_\-=])""")

    /** The same segment inside a percent-encoded DevTools `uri=`: `%2F<token>%3D%2F`. */
    private val ENCODED_TOKEN_SEGMENT = Regex("""(?i)(%3A%2F%2F[^%\s&#]+(?:%3A\d+)?%2F)[A-Za-z0-9_\-]+(?:%3D)+""")

    /** [text] with every VM Service auth code replaced by [PLACEHOLDER]. */
    fun scrub(text: String): String {
        if (!text.contains("://") && !text.contains("%2F", ignoreCase = true)) return text
        val plain = TOKEN_SEGMENT.replace(text) { it.groupValues[1] + PLACEHOLDER }
        return ENCODED_TOKEN_SEGMENT.replace(plain) { it.groupValues[1] + PLACEHOLDER }
    }

    /**
     * Scrubs a VM Service `Event` object in place and returns it: every string in it, the
     * base64 `bytes` of a `WriteEvent` (stdout/stderr), and the whole `value` of the two
     * [ADDRESS_EXTENSIONS], which Flutter may report in a form the pattern would miss.
     */
    fun scrubEvent(event: JsonObject): JsonObject {
        scrubStrings(event)
        scrubAddressExtension(event)
        scrubWriteBytes(event)
        return event
    }

    private fun scrubAddressExtension(event: JsonObject) {
        if (event.stringOrNull("extensionKind") != STATE_CHANGED) return
        val data = event.get("extensionData") as? JsonObject ?: return
        if (data.stringOrNull("extension") in ADDRESS_EXTENSIONS && data.has("value")) {
            data.addProperty("value", PLACEHOLDER)
        }
    }

    private fun scrubWriteBytes(event: JsonObject) {
        val kind = event.stringOrNull("kind")
        if (kind != "WriteEvent") return
        val bytes = event.stringOrNull("bytes") ?: return
        val decoded = try {
            String(Base64.getDecoder().decode(bytes), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return
        }
        val scrubbed = scrub(decoded)
        if (scrubbed != decoded) {
            event.addProperty("bytes", Base64.getEncoder().encodeToString(scrubbed.toByteArray(Charsets.UTF_8)))
        }
    }

    private fun scrubStrings(element: JsonElement) {
        when (element) {
            is JsonObject -> element.entrySet().forEach { entry ->
                val value = entry.value
                if (value.isString()) entry.setValue(JsonPrimitive(scrub(value.asString))) else scrubStrings(value)
            }
            is JsonArray -> for (index in 0 until element.size()) {
                val value = element[index]
                if (value.isString()) element[index] = JsonPrimitive(scrub(value.asString)) else scrubStrings(value)
            }
            else -> Unit
        }
    }

    private fun JsonElement.isString() = this is JsonPrimitive && isString

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isString() }?.asString
}
