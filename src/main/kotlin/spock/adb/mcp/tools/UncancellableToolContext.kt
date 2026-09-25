package spock.adb.mcp.tools

/**
 * [base], for a transport with no way to cancel a request: the MCP HTTP endpoint.
 *
 * An HTTP client that gives up on a call just closes its connection, and nothing on this side
 * notices, so the call runs to its own limit on one of the server's few threads. Saying so
 * through [canCancel] lets a tool that waits keep that limit short. Everything else is [base]'s.
 */
class UncancellableToolContext(private val base: ToolContext) : ToolContext by base {

    override val canCancel: Boolean get() = false
}
