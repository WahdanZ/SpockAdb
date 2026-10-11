package spock.adb.flutter.vmservice

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * A Dart VM Service address, normalised to the WebSocket form a client connects to:
 * `ws://127.0.0.1:<port>/<token>=/ws`.
 *
 * The token in the path is the only thing that guards the VM Service, and the VM Service can
 * evaluate code in the app [FR9]. So the address is accepted on loopback only, and nothing this
 * class prints contains the token: [toString] is [redacted]. Only [webSocketUri] carries it, and
 * that is for the WebSocket handshake, not for display.
 */
class VmServiceUri private constructor(
    val host: String,
    val port: Int,
    private val token: String,
    val secure: Boolean,
) {

    /** The address to open the WebSocket on. Carries the token: never log or show it. */
    val webSocketUri: URI
        get() = URI.create("${scheme()}://${hostForUri()}:$port/${tokenSegment(token)}ws")

    /** The same VM Service seen on another port, as after an `adb forward` to the host. */
    fun withPort(newPort: Int): VmServiceUri = VmServiceUri(LOOPBACK_V4, newPort, token, secure)

    /** `ws://127.0.0.1:<port>/<redacted>/ws`; the form for logs, history and the UI. */
    fun redacted(): String {
        val segment = if (token.isEmpty()) "" else "${Redaction.PLACEHOLDER}/"
        return "${scheme()}://${hostForUri()}:$port/${segment}ws"
    }

    override fun toString(): String = redacted()

    override fun equals(other: Any?): Boolean =
        other is VmServiceUri && other.host == host && other.port == port && other.token == token &&
            other.secure == secure

    override fun hashCode(): Int = listOf(host, port, token, secure).hashCode()

    private fun scheme() = if (secure) "wss" else "ws"

    private fun hostForUri() = if (host.contains(':')) "[$host]" else host

    companion object {
        private const val LOOPBACK_V4 = "127.0.0.1"
        private const val MAX_PORT = 65_535
        private val LOOPBACK_HOSTS = setOf(LOOPBACK_V4, "::1", "0:0:0:0:0:0:0:1", "localhost")

        /** URLs in a pasted line: `flutter run` and logcat print one inside a sentence. */
        private val URL_IN_TEXT = Regex("""(?i)\b(?:https?|wss?)://[^\s"'<>]+""")

        /** DevTools carries the VM Service address as `uri=`, in the query or after `#/?`. */
        private val URI_PARAM = Regex("""[?&]uri=([^&#\s]+)""")

        /** Not auth codes: the `ws` endpoint and DevTools, of an address with auth codes off. */
        private val NOT_TOKENS = setOf("ws", "devtools")

        private const val HEX = 16

        /** `%XX`. */
        private const val ESCAPE_CHARS = 3

        /** A VM Service auth code: base64url, padded with `=` (Dart encodes 8 random bytes). */
        private val TOKEN = Regex("""[A-Za-z0-9_\-]+=*""")

        /**
         * Reads any form a developer is likely to paste — `flutter run`'s `http://127.0.0.1:P/T=/`
         * (with or without the trailing slash), its `ws://…/T=/ws`, `https`/`wss`, a logcat line
         * with the address inside it, or a DevTools link with the address in `?uri=` or `#/?uri=`.
         * When the text holds several URLs, the first with an auth code wins over the first one:
         * `flutter run` prints the DevTools address, which has none, beside the VM Service's.
         *
         * @throws IllegalArgumentException with a message fit to show, when [text] holds no VM
         * Service address or one that is not on this machine's loopback.
         */
        fun parse(text: String): VmServiceUri {
            val urls = URL_IN_TEXT.findAll(text.trim()).map { it.value.trimEnd('.', ',', ')', ';') }.toList()
            require(urls.isNotEmpty()) { "No VM Service address found. Paste the http:// or ws:// URI." }
            val parsed = urls.map { url ->
                val inner = URI_PARAM.find(url)?.groupValues?.get(1)?.let(::decodeParam)
                try {
                    Result.success(fromUrl(inner ?: url))
                } catch (e: IllegalArgumentException) {
                    Result.failure(e)
                }
            }
            val chosen = parsed.firstOrNull { it.getOrNull()?.token?.isNotEmpty() == true }
                ?: parsed.firstOrNull { it.isSuccess }
                ?: parsed.first()
            return chosen.getOrThrow()
        }

        /** Whether [host] names this machine's loopback: the only place an address is accepted. */
        internal fun isLoopback(host: String): Boolean =
            host.removePrefix("[").removeSuffix("]").lowercase() in LOOPBACK_HOSTS

        /** [parse], or null instead of an exception. */
        fun parseOrNull(text: String): VmServiceUri? = try {
            parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun fromUrl(url: String): VmServiceUri {
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("Not a VM Service address: ${shown(url)}")
            }
            val scheme = uri.scheme?.lowercase()
            val secure = scheme == "https" || scheme == "wss"
            require(scheme in setOf("http", "https", "ws", "wss")) {
                "Not a VM Service address (scheme ${uri.scheme}): ${shown(url)}"
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            require(host != null && host in LOOPBACK_HOSTS) {
                "Refusing a VM Service on ${host ?: "an unknown host"}: only 127.0.0.1, ::1 or localhost is " +
                    "accepted, because the address grants code execution in the app. Forward the port to " +
                    "this machine first."
            }
            require(uri.port in 1..MAX_PORT) { "The VM Service address has no port: ${shown(url)}" }
            return VmServiceUri(host, uri.port, tokenOf(uri, url), secure)
        }

        /** The first path segment, unless it is an endpoint of an address with no auth code. */
        private fun tokenOf(uri: URI, url: String): String {
            val first = uri.rawPath.orEmpty().split('/').firstOrNull { it.isNotEmpty() } ?: return ""
            if (first in NOT_TOKENS) return ""
            require(TOKEN.matches(first)) { "Not a VM Service address: ${shown(url)}" }
            return first
        }

        /**
         * [url] fit for a message: up to its authority. A malformed or off-loopback address is
         * not one [Redaction] knows to scrub, and its path may still be a token.
         */
        private fun shown(url: String): String {
            val authorityStart = url.indexOf("://").takeIf { it >= 0 }?.plus("://".length) ?: return "…"
            val pathStart = url.indexOf('/', authorityStart)
            return if (pathStart < 0) url else url.substring(0, pathStart) + "/…"
        }

        private fun tokenSegment(token: String) = if (token.isEmpty()) "" else "$token/"

        /**
         * A DevTools `uri=` is usually percent-encoded — sometimes twice — but `flutter run`
         * prints it raw.
         */
        private fun decodeParam(value: String): String = decodePercent(decodePercent(value))

        /**
         * `%XX` sequences decoded as UTF-8, and nothing else: unlike a form decoder, `+` stays a
         * `+`. A `%` not followed by two hex digits is kept as it is.
         */
        internal fun decodePercent(text: String): String {
            if ('%' !in text) return text
            val out = StringBuilder(text.length)
            val bytes = ByteArrayOutputStream()
            var index = 0
            while (index < text.length) {
                val escape = text[index] == '%' && index + 2 <= text.lastIndex
                val high = if (escape) Character.digit(text[index + 1], HEX) else -1
                val low = if (high >= 0) Character.digit(text[index + 2], HEX) else -1
                if (low >= 0) {
                    bytes.write(high * HEX + low)
                    index += ESCAPE_CHARS
                } else {
                    if (bytes.size() > 0) out.append(bytes.toString(Charsets.UTF_8)).also { bytes.reset() }
                    out.append(text[index])
                    index++
                }
            }
            if (bytes.size() > 0) out.append(bytes.toString(Charsets.UTF_8))
            return out.toString()
        }
    }
}
