package spock.adb.mcp

/**
 * What one client connection agreed at `initialize`: the protocol version, which decides the
 * shape of `tools/list` and of tool results.
 *
 * One per stdio connection, since a connection is a session there. HTTP is stateless, so each
 * request carries its version in the `MCP-Protocol-Version` header instead and gets a session
 * built from it; see [McpHttpServer].
 *
 * @param protocolVersion null until `initialize`, which means the oldest shape: exactly what the
 *   server sent before it negotiated anything.
 */
class McpSession(protocolVersion: String? = null) {

    @Volatile
    var protocolVersion: String? = protocolVersion
        internal set

    /** Whether results may carry `structuredContent` and tools an `outputSchema`, as 2025-06-18 defines. */
    val structuredOutput: Boolean get() = protocolVersion == STRUCTURED_OUTPUT_VERSION

    /** Whether a tool's `title` goes at the top level, as 2025-06-18 defines it. */
    val topLevelTitle: Boolean get() = protocolVersion == STRUCTURED_OUTPUT_VERSION

    /**
     * Whether a tool's `title` goes in its annotations, where 2025-03-26 defines it. 2024-11-05 has
     * no title at all, so a client on it is not sent one it would ignore.
     */
    val annotationTitle: Boolean get() = protocolVersion == ANNOTATION_TITLE_VERSION

    companion object {
        /** Newest first. The first is what a client asking for anything else is offered. */
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        val LATEST_VERSION: String = SUPPORTED_VERSIONS.first()

        private const val STRUCTURED_OUTPUT_VERSION = "2025-06-18"
        private const val ANNOTATION_TITLE_VERSION = "2025-03-26"

        /**
         * The version to answer `initialize` with: the client's own when this server speaks it,
         * otherwise the latest this server does, as the spec asks. The client decides whether it
         * can work with that.
         */
        fun negotiate(requested: String?): String = requested?.takeIf { it in SUPPORTED_VERSIONS } ?: LATEST_VERSION
    }
}
