package spock.adb.commandcenter

/**
 * Classifies a shell command by how much damage running it can do.
 *
 * Shared by the Command Center and the MCP `android_run_adb_command` tool so a command that
 * needs confirmation in one place needs it in the other. Two lists would drift, and the one
 * that drifted would be the one letting something through.
 *
 * This is a heuristic on a string, not a sandbox. It exists so an obviously destructive
 * command is flagged before it runs, not to make arbitrary shell safe.
 */
object DangerousCommands {

    enum class Verdict { SAFE, DESTRUCTIVE, REFUSED }

    /** Wipes the device or the developer's data rather than the app's. Never offered. */
    private val REFUSED = listOf(
        "rm -rf /" to "would delete the device filesystem",
        "--wipe_data" to "would factory reset the device",
        "recovery --wipe_data" to "would factory reset the device",
        "wipe data" to "would factory reset the device",
        "mkfs" to "would reformat a filesystem",
        "dd if=" to "would write raw blocks",
    )

    /** Destroys app or system state. Allowed, but only after explicit confirmation. */
    private val DESTRUCTIVE = listOf(
        "pm clear" to "deletes all data for an app",
        "pm uninstall" to "uninstalls an app",
        "pm revoke" to "revokes a permission",
        "pm disable" to "disables a package or component",
        "rm " to "deletes files on the device",
        "reboot" to "reboots the device",
        "svc power" to "changes power state",
        "settings put" to "changes a system setting",
        "content delete" to "deletes content provider rows",
        "sqlite3" to "can modify app databases directly",
    )

    fun classify(command: String): Verdict {
        val normalised = command.trim().lowercase()
        if (normalised.isEmpty()) return Verdict.SAFE

        if (REFUSED.any { normalised.contains(it.first) }) return Verdict.REFUSED
        if (DESTRUCTIVE.any { normalised.contains(it.first) }) return Verdict.DESTRUCTIVE
        return Verdict.SAFE
    }

    /** Why a command was flagged, for the confirmation dialog. Null when it is not flagged. */
    fun explain(command: String): String? {
        val normalised = command.trim().lowercase()
        REFUSED.firstOrNull { normalised.contains(it.first) }?.let { return "This command ${it.second}." }
        DESTRUCTIVE.firstOrNull { normalised.contains(it.first) }?.let { return "This command ${it.second}." }
        return null
    }
}
