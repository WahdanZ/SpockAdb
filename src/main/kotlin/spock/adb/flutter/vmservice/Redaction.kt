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
 *
 * Only a path right after a loopback (or unspecified, `0.0.0.0`/`[::]`) host and a port is taken
 * for a token — the only place the VM Service serves — so `https://api.example.com/Zm9v=` in an
 * app's log stays as it is. The address is found raw, with or without its scheme, and
 * percent-encoded once or twice, as DevTools links carry it.
 */
object Redaction {

    const val PLACEHOLDER = "<redacted>"

    /** The two extensions whose state-changed `value` is an address. */
    val ADDRESS_EXTENSIONS = setOf(
        "ext.flutter.connectedVmServiceUri",
        "ext.flutter.activeDevToolsServerAddress",
    )

    private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"

    /** A separator raw or percent-encoded, once or twice: `:`, `%3A`, `%253A`. */
    private const val PERCENT = "%(?:25){0,2}"
    private const val COLON = "(?::|${PERCENT}3[Aa])"
    private const val SLASH = "(?:/|${PERCENT}2[Ff])"
    private const val EQUALS = "(?:=|${PERCENT}3[Dd])"
    private const val OPEN = """(?:\[|${PERCENT}5[Bb])"""
    private const val CLOSE = "(?:]|${PERCENT}5[Dd])"
    private const val IPV6 = "$OPEN(?:$COLON${COLON}1?|0(?:${COLON}0){6}${COLON}[01])$CLOSE"
    private const val HOST = """(?:127\.0\.0\.1|0\.0\.0\.0|[Ll][Oo][Cc][Aa][Ll][Hh][Oo][Ss][Tt]|$IPV6)"""
    private const val SCHEME = """[A-Za-z][A-Za-z0-9+.\-]*$COLON$SLASH$SLASH"""

    /** Scheme (or none, but then not inside a longer name), host, port, slash. */
    private const val AUTHORITY = """(?:$SCHEME|(?<![A-Za-z0-9.\-]))$HOST$COLON\d{1,5}$SLASH"""

    /**
     * The auth code: base64url, padded (`=` or `%3D`), at least 8 characters. Unpadded, it must
     * hold a capital or a digit, which random base64 does and DevTools page names (`inspector`,
     * `devtools`) do not.
     */
    private const val TOKEN =
        """(?:[A-Za-z0-9_\-]{8,}(?:$EQUALS)+|(?=[a-z_\-]*[A-Z0-9])[A-Za-z0-9_\-]{8,}(?![A-Za-z0-9_\-]))"""

    private val TOKEN_SEGMENT = Regex("($AUTHORITY)$TOKEN")

    /** A DevTools `uri=` value, read again decoded: an encoding the pattern misses is still caught. */
    private val URI_PARAM = Regex("""([?&#]uri=)([^&#\s"'<>]+)""")

    /** Without one of these, no address the pattern knows can be in the text. */
    private val HINTS = listOf("127.0.0.1", "0.0.0.0", "localhost", "::", "%3A", "%253A", "%25253A", "0:0:0:0")

    /** [text] with every VM Service auth code replaced by [PLACEHOLDER]. */
    fun scrub(text: String): String {
        if (HINTS.none { text.contains(it, ignoreCase = true) }) return text
        val plain = TOKEN_SEGMENT.replace(text) { it.groupValues[1] + PLACEHOLDER }
        if (!plain.contains("uri=")) return plain
        return URI_PARAM.replace(plain) { match ->
            val decoded = VmServiceUri.decodePercent(VmServiceUri.decodePercent(match.groupValues[2]))
            val scrubbed = TOKEN_SEGMENT.replace(decoded) { it.groupValues[1] + PLACEHOLDER }
            if (scrubbed == decoded) match.value else match.groupValues[1] + scrubbed
        }
    }

    /** A copy of [element] with [scrub] applied to every string in it: error `data`, results. */
    fun scrubJson(element: JsonElement): JsonElement {
        if (element is JsonPrimitive) return if (element.isString) JsonPrimitive(scrub(element.asString)) else element
        return element.deepCopy().also(::scrubStrings)
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

    /**
     * Replaces the `value` of an [ADDRESS_EXTENSIONS] call's result: the extension answers with
     * the address itself.
     */
    fun scrubExtensionResult(method: String, result: JsonObject): JsonObject {
        if (method in ADDRESS_EXTENSIONS && result.has("value")) result.addProperty("value", PLACEHOLDER)
        return result
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
