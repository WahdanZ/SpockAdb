package spock.adb.flutter.dtd

import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceUri
import java.net.URI
import java.net.URISyntaxException

/**
 * A Dart Tooling Daemon address: `ws://127.0.0.1:<port>/<secret>` (spike S12).
 *
 * Unlike a VM Service address there is no trailing `/ws`, and the whole path is the secret that
 * lets a client in. A DTD can hand out the VM Service address of every app registered with it, so
 * its secret is treated like a VM Service token [FR9]: loopback only, never printed. [toString]
 * is [redacted]; only [webSocketUri] carries the secret, for the handshake.
 *
 * The registry file it comes from is any process's to write, so [parse] vets it like a paste.
 */
class DtdUri private constructor(
    val host: String,
    val port: Int,
    private val secret: String,
    val secure: Boolean,
) {

    /** The address to open the WebSocket on. Carries the secret: never log or show it. */
    val webSocketUri: URI get() = URI.create("${scheme()}://${hostForUri()}:$port/$secret")

    /** `ws://127.0.0.1:<port>/<redacted>`; the form for logs and messages. */
    fun redacted(): String {
        val path = if (secret.isEmpty()) "" else Redaction.PLACEHOLDER
        return "${scheme()}://${hostForUri()}:$port/$path"
    }

    /**
     * [text] with this daemon's secret taken out — by value, so a secret that does not look like
     * a VM Service token (no `=` padding) is caught too — and any VM Service token besides.
     */
    fun scrub(text: String): String {
        val scrubbed = Redaction.scrub(text)
        return if (secret.isEmpty()) scrubbed else scrubbed.replace(secret, Redaction.PLACEHOLDER)
    }

    override fun toString(): String = redacted()

    override fun equals(other: Any?): Boolean =
        other is DtdUri && other.host == host && other.port == port && other.secret == secret &&
            other.secure == secure

    override fun hashCode(): Int = listOf(host, port, secret, secure).hashCode()

    private fun scheme() = if (secure) "wss" else "ws"

    private fun hostForUri() = if (host.contains(':')) "[$host]" else host

    companion object {
        private const val MAX_PORT = 65_535

        /**
         * Reads a registry file's `wsUri`.
         *
         * @throws IllegalArgumentException with a redacted message when it is not a `ws`/`wss`
         * address on this machine's loopback.
         */
        fun parse(text: String): DtdUri {
            val uri = try {
                URI(text.trim())
            } catch (_: URISyntaxException) {
                // The text is not quoted: a secret in a form Redaction does not know would leak.
                throw IllegalArgumentException("Not a Dart Tooling Daemon address")
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "ws" || scheme == "wss") { "Not a Dart Tooling Daemon address (scheme ${uri.scheme})" }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            require(host != null && VmServiceUri.isLoopback(host)) {
                "Refusing a Dart Tooling Daemon on ${host ?: "an unknown host"}: only loopback is accepted"
            }
            require(uri.port in 1..MAX_PORT) { "The Dart Tooling Daemon address has no port" }
            // Kept as written: its form is the daemon's business (base64url with `=` in S12).
            val secret = uri.rawPath.orEmpty().removePrefix("/")
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Not a Dart Tooling Daemon address: unexpected query"
            }
            return DtdUri(host, uri.port, secret, scheme == "wss")
        }

        /** [parse], or null instead of an exception. */
        fun parseOrNull(text: String): DtdUri? = try {
            parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
