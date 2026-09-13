package spock.adb.command

/**
 * The device's global HTTP proxy, as `host:port`.
 *
 * Android stores this in a single `settings global http_proxy` string, and clears it by
 * writing the sentinel `:0` rather than removing the row. A device that has never had a
 * proxy set answers the literal string "null". All three of those mean "no proxy", so
 * [parse] collapses them into a null [HttpProxy] instead of leaving each caller to
 * recognise them — the plugin previously had no proxy support at all, and getting this
 * wrong would show a device as proxied when it is not.
 */
data class HttpProxy(val host: String, val port: Int) {

    /** The value written to `settings global http_proxy`. */
    override fun toString(): String = "$host:$port"

    companion object {

        /** What Android writes to mean "no proxy". Removing the row is not how it is cleared. */
        const val CLEARED = ":0"

        private val PORT_RANGE = 1..65535

        /**
         * Reads `settings get global http_proxy` output. Null when no proxy is set.
         *
         * The host is split on the *last* colon so an IPv6 literal survives the round trip.
         * [CLEARED] needs no case of its own: it has no host, so it is rejected below.
         */
        fun parse(raw: String?): HttpProxy? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value.equals("null", ignoreCase = true)) return null

            val separator = value.lastIndexOf(':')
            if (separator <= 0 || separator == value.length - 1) return null

            val port = value.substring(separator + 1).toIntOrNull()
            return if (port != null && port in PORT_RANGE) {
                HttpProxy(value.substring(0, separator), port)
            } else {
                null
            }
        }

        /**
         * Validates a host and port the user or an agent supplied.
         *
         * @throws IllegalArgumentException with a message naming what to fix. The value ends
         *   up on a shell command line, and while [spock.adb.ShellQuote] is the security
         *   boundary, a host with whitespace in it is a typo worth catching here rather than
         *   writing to the device and leaving the user to wonder why nothing routes.
         */
        fun of(host: String, port: Int): HttpProxy {
            val trimmed = host.trim()
            require(trimmed.isNotEmpty()) {
                "Proxy host is empty. Enter the host your proxy listens on, for example 192.168.1.10."
            }
            require(trimmed.none { it.isWhitespace() }) {
                "Proxy host '$trimmed' contains whitespace."
            }
            // No hostname or IPv4 address has a colon in it, and an unbracketed IPv6 address
            // cannot be told apart from host:port once it is written to the device.
            require(':' !in trimmed || trimmed.isBracketed()) {
                "Proxy host '$trimmed' contains a colon. Wrap an IPv6 address in brackets, for example [::1]."
            }
            require(port in PORT_RANGE) {
                "Proxy port $port is out of range. Use a port between ${PORT_RANGE.first} and ${PORT_RANGE.last}."
            }
            return HttpProxy(trimmed, port)
        }

        /**
         * Parses a typed `host:port`, for the tool window field and for agents that send the
         * pair as one string.
         *
         * @throws IllegalArgumentException with a message naming what to fix.
         */
        fun fromInput(input: String): HttpProxy {
            val value = input.trim()
            require(value.isNotEmpty()) {
                "Enter the proxy as host:port, for example 192.168.1.10:8888."
            }

            // A trailing bracket is a bracketed address with no port: its last colon is inside
            // the address, not before a port.
            val separator = value.lastIndexOf(':')
            require(separator > 0 && separator < value.length - 1 && !value.endsWith(']')) {
                "'$value' is not host:port. Enter both parts, for example 192.168.1.10:8888."
            }

            // Checked on the whole input, before splitting: split on its last colon, "fe80::1"
            // would otherwise become host "fe80:" on port 1.
            val host = value.substring(0, separator)
            require(':' !in host || host.isBracketed()) {
                "'$value' looks like an IPv6 address. Wrap it in brackets and add the port, for example [::1]:8888."
            }

            val rawPort = value.substring(separator + 1)
            val port = rawPort.toIntOrNull()
                ?: throw IllegalArgumentException("'$rawPort' is not a port number.")

            return of(host, port)
        }

        /**
         * The device's actual state, as the tool window shows it next to the field.
         *
         * A failed read is "unknown" rather than "direct": guessing no proxy for a device that
         * could not be asked is exactly the wrong answer this feature exists to avoid.
         */
        fun describeDevice(read: Result<HttpProxy?>): String = read.fold(
            onSuccess = { proxy -> proxy?.let { "Device: via $it" } ?: "Device: direct" },
            onFailure = { "Device: unknown" },
        )

        private fun String.isBracketed(): Boolean = startsWith('[') && endsWith(']')
    }
}

/**
 * What a proxy write asked for, against what the device reports once it has been read back.
 *
 * `settings put` exits 0 even where the write does not take, so both the tool window and the
 * MCP tools report from this rather than from the value they sent. It is built in one place
 * because the read-back is easy to leave out — the panel's Clear once reported success
 * without it.
 *
 * @param requested the proxy asked for, or null for a clear.
 * @param applied what the device holds afterwards, or null when it connects directly.
 */
data class HttpProxyWrite(val requested: HttpProxy?, val applied: HttpProxy?) {

    val took: Boolean get() = requested == applied

    val message: String
        get() = when {
            took && requested != null -> "HTTP proxy set to $applied."
            took -> "HTTP proxy cleared; the device connects directly."
            requested != null ->
                "Asked the device for $requested but it reports ${applied ?: "no proxy"}. " +
                    "The setting did not stick."
            else -> "Cleared the proxy but the device still reports $applied."
        }
}
