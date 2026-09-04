package spock.adb.mcp.tools

import com.google.gson.JsonObject

/**
 * One strongly typed operation an MCP client (or the plugin's own AI layer) can invoke.
 *
 * Tools are deliberately semantic rather than a shell passthrough: `android_open_deep_link`
 * tells an agent what the operation *means*, where `adb shell am start ...` does not. That
 * is what lets a client reason about, and a user audit, what is being done to their device.
 *
 * Implementations must not talk to ADB directly — they go through the same services the
 * tool window uses, so there is one implementation of every device operation.
 */
interface AdbTool {

    /** MCP tool name, e.g. `android_get_current_activity`. Stable; clients bind to it. */
    val name: String

    /** Shown to the agent. Say what it does and when to use it. */
    val description: String

    /** Decides whether the call can run automatically. See [ToolSafety]. */
    val safety: ToolSafety

    /** JSON Schema for the arguments object. */
    val inputSchema: JsonObject

    /**
     * Runs the tool. Never called on the EDT.
     *
     * Throwing is acceptable — the caller converts it into an MCP error result — but a
     * message the agent can act on is far more useful than a stack trace.
     */
    fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/**
 * How much trust a tool call requires.
 *
 * An agent must never be able to destroy device state without the developer agreeing to
 * that specific call, so the level is a property of the tool rather than a runtime flag a
 * client could set.
 */
enum class ToolSafety {

    /** Observes only. Cannot change device or app state. Runs automatically. */
    READ_ONLY,

    /**
     * Changes state, but only in ways a developer routinely does by hand and can undo by
     * repeating a normal action — launching an app, pressing back, opening a deep link.
     * Runs automatically.
     */
    SAFE_ACTION,

    /**
     * Destroys state or grants privilege: uninstall, clear app data, revoke permissions,
     * arbitrary shell. **Always** requires explicit confirmation from the developer, per
     * call. Never auto-approved, and never silently retried.
     */
    DESTRUCTIVE,
}
