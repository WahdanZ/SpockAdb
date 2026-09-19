package spock.adb.command

/**
 * Whether a `pm grant` or `pm revoke` did anything.
 *
 * Both print nothing at all when they work, and an exception when they do not — a permission
 * the app never requested, one that is granted at install and cannot be changed, one the
 * platform reserves. Neither sets an exit status the shell reports, and the plugin discarded
 * the output entirely, so every failure was announced as a success.
 */
internal object PermissionChange {

    /** The reason it failed, or null when it worked. */
    fun failureOf(output: String): String? {
        val text = output.trim()
        if (text.isEmpty()) return null
        // The last line carries the cause; the first is "Exception occurred while executing".
        val cause = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: text
        return cause.substringAfter("SecurityException: ", cause)
            .substringAfter("IllegalArgumentException: ")
            .ifBlank { text }
    }
}
