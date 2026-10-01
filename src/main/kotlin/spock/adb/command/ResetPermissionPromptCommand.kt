package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.isAppInstall
import java.util.concurrent.TimeUnit

/**
 * Makes Android ask for a permission again, without clearing the app's data.
 *
 * Once a permission carries `USER_FIXED`, `requestPermissions()` answers denied straight away and
 * shows no dialog. The only reset the plugin offered was Clear data, which also wipes the login,
 * the preferences and the database. `pm clear-permission-flags` removes just the two flags the
 * user's answers set.
 *
 * Input: first is the package, second is the permission. Returns what to tell the user.
 */
class ResetPermissionPromptCommand : Command<Pair<String, String>, String> {

    override fun execute(p: Pair<String, String>, project: Project, device: IDevice): String {
        val (pkg, permission) = p
        check(device.isAppInstall(pkg)) { "Application $pkg is not installed on this device" }
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(
            "pm clear-permission-flags --user 0 ${ShellQuote.quote(pkg)} ${ShellQuote.quote(permission)} " +
                "user-set user-fixed",
            receiver,
            TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        // `pm` sets no exit status, and a device without the subcommand may print nothing useful,
        // so only the package's own account of the flags afterwards can say it worked.
        val after = GetApplicationPermission().execute(pkg, project, device).find { it.name == permission }
        ResetPermissionPrompt.failureOf(receiver.toString(), after, permission)?.let { error(it) }
        return "${permission.substringAfterLast('.')} will be asked for again"
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}

/**
 * Whether a `pm clear-permission-flags` did anything, decided from the package read back after it.
 *
 * Like `pm grant` and `pm revoke` (see [PermissionChange]) it sets no exit status, and on a device
 * whose package manager predates the subcommand it prints usage text rather than an error.
 */
internal object ResetPermissionPrompt {

    /**
     * The reason it failed, or null when the permission is denied and carries neither flag.
     *
     * A granted permission is a failure too: nothing will prompt for it, so "will be asked for
     * again" would not be true.
     */
    fun failureOf(output: String, after: RuntimePermission?, permission: String): String? = when {
        after == null -> "$permission is not a runtime permission of this app"
        after.granted -> "$permission is granted; there is no prompt to reset"
        !after.userFixed && !after.userSet -> null
        output.contains("unknown command", ignoreCase = true) ->
            "This device's package manager has no clear-permission-flags command"
        else -> {
            val said = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            val still = if (after.userFixed) {
                "The device still marks $permission as won't ask again"
            } else {
                "The device still has the USER_SET flag on $permission"
            }
            still + said?.let { ": $it" }.orEmpty()
        }
    }
}
