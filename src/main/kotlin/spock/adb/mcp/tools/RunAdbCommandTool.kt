package spock.adb.mcp.tools

import com.google.gson.JsonObject

/**
 * `android_run_adb_command` — the escape hatch, deliberately awkward.
 *
 * Every other tool is semantic, so an agent and the developer reading the audit log can see
 * what an operation means. This one cannot offer that, so it compensates with a hard
 * confirmation, a bounded timeout, capped output, and an audit entry for every call.
 *
 * It is intentionally *not* the first tool an agent should reach for; the description says
 * so, because a generic shell tool that is easy to use will be used instead of the typed
 * ones, and the safety model is only as good as what agents actually call.
 */
class RunAdbCommandTool : AdbTool {

    override val name = "android_run_adb_command"
    override val description =
        "DANGEROUS ESCAPE HATCH. Run an arbitrary `adb shell` command on the device. " +
            "Every call requires the developer to read the command and approve it, so it is " +
            "slow and interrupts them. Prefer a specific tool — android_launch_app, " +
            "android_get_logcat, android_open_deep_link and the rest — and only use this " +
            "when no typed tool can express what you need. Say why you need it."
    override val safety = ToolSafety.DESTRUCTIVE

    override val inputSchema: JsonObject = Schema.obj {
        string("command", "The shell command to run on the device, without the 'adb shell' prefix.", required = true)
        string("reason", "Why a typed tool cannot do this. Shown to the developer when approving.", required = true)
        integer("timeoutSeconds", "Timeout in seconds. Defaults to 20, maximum 120.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val command = arguments.requiredString("command").trim()
        val reason = arguments.requiredString("reason").trim()

        validate(command)?.let { return ToolResult.error(it) }

        val timeout = arguments
            .optionalInt("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
            .toLong()

        val approved = context.confirmDestructive(
            name,
            "Run this command on the device:\n\n    $command\n\nThe agent says: $reason",
            target,
        )
        if (!approved) {
            return ToolResult.error("The developer declined to run: $command")
        }

        val output = McpShell.run(target.device, command, timeoutSeconds = timeout)
        return ToolResult.text(output.ifBlank { "The command produced no output." })
    }

    /**
     * Rejects a small set of commands whose blast radius reaches past the app under test.
     *
     * This is a guard rail, not a sandbox: the developer still approves every call, and a
     * determined agent could phrase these differently. It exists to stop an obviously
     * catastrophic command reaching the confirmation dialog at all, where a tired developer
     * might wave it through.
     */
    private fun validate(command: String): String? {
        if (command.isBlank()) return "The command is empty."

        val normalised = command.lowercase()
        BLOCKED.forEach { (pattern, explanation) ->
            if (normalised.contains(pattern)) {
                return "Refused: this command $explanation. Blocked pattern: '$pattern'."
            }
        }
        return null
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 20
        const val MAX_TIMEOUT_SECONDS = 120

        /** Commands that wipe the device or the developer's data rather than the app's. */
        val BLOCKED = listOf(
            "rm -rf /" to "would delete the device filesystem",
            "recovery --wipe_data" to "would factory reset the device",
            "--wipe_data" to "would factory reset the device",
            "mkfs" to "would reformat a filesystem",
            "dd if=" to "would write raw blocks",
            "wipe data" to "would factory reset the device",
        )
    }
}
