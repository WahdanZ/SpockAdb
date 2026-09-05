package spock.adb.mcp

/**
 * Whether a tool may run at all, before anything about the call is considered.
 *
 * The per-call confirmation on destructive tools has been the only boundary until now, which
 * leaves a developer no way to expose the read-only tools and nothing else. That gap is not
 * theoretical: `android_push_file` and `android_pull_file` are individually reasonable and
 * compose into reading any file on the machine. A confirmation dialog cannot see a
 * composition; an allow-list does not have to.
 *
 * The registry is never mutated to express this. A disabled tool is still registered, still
 * described in `tools/list`, and still refuses when called — hiding it would make an agent
 * hunt for a tool it can see documented, and would leave the audit trail silent about the
 * attempt.
 */
object ToolGate {

    /**
     * The refusal an agent sees. Names the tool and where the decision lives, so the model can
     * tell "you turned this off" from "this failed", and say so to the developer rather than
     * retrying.
     */
    fun refusal(toolName: String): String =
        "The tool '$toolName' is disabled in Settings > Tools > Spock ADB. It was not run. " +
            "Enable it there if you want it available, or use a different tool."

    /**
     * @param disabled tool names the developer switched off, matched exactly — these come from
     *   the registry's own names, so a near-miss means the setting is stale rather than that a
     *   tool should quietly run.
     */
    fun isEnabled(toolName: String, disabled: Set<String>): Boolean = toolName !in disabled
}
