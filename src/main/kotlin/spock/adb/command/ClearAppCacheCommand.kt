package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.device.ops.AppOperations
import java.util.concurrent.TimeUnit

/**
 * The shell side of "clear the cache, and nothing else", kept free of [IDevice] so it is
 * testable without a device.
 *
 * Clearing goes through `run-as`, never `pm clear --cache-only`. On a device that predates
 * that flag, `pm clear` consumes the unknown option and clears the package *in full* — a
 * silent total data wipe when the user asked for a cache drop. `run-as` cannot do that:
 * every path it touches is relative, inside the app's own data directory. The price is that
 * it needs a debuggable build, which is what a developer is running from the open project
 * anyway; when it is not available the refusal is explicit and names the alternative.
 *
 * The `rm` reports its own exit status in the same command rather than being checked by a
 * second `ls`. A separate readback was wrong three ways on a real device: toybox `ls` prints
 * a `dirname:` header for each operand when given more than one, so an empty cache read back
 * as two leftover files and every successful clear reported failure; it prints nothing at all
 * for the whole invocation when one operand is missing, hiding a genuine leftover in the
 * other; and it is a second round trip, so a running app — an image loader, a crash reporter,
 * a WebView — can legitimately refill the cache in the gap and be reported as a failed clear.
 * `echo rc=$?` has none of those problems: it is the status of the `rm` that just ran.
 */
internal object AppCacheShell {

    /**
     * Relative on purpose. An absolute `/data/data/<pkg>/...` would work only for user 0 and
     * puts a path outside the app's sandbox one typo away from the `rm`.
     */
    val CACHE_DIRS = listOf("cache", "code_cache")

    fun clearCommand(packageName: String): String =
        RunAs.command(packageName, "rm -rf ${CACHE_DIRS.joinToString(" ") { "./$it" }}; echo rc=$?")

    /**
     * `null` when the device reported `rc=0`, which is the `rm` saying it succeeded.
     *
     * Everything else is a failure. `run-as` that refuses never starts the shell, so the
     * status line is missing entirely — the two shapes it actually produces get an actionable
     * sentence, and anything else is quoted verbatim rather than flattened into "failed".
     */
    fun failureMessage(packageName: String, output: String): String? =
        when (val outcome = RunAs.classify(output)) {
            // The echo runs only if run-as handed the script to a shell at all, so rc=0 is proof
            // the rm ran and succeeded — regardless of anything else printed on the way.
            is RunAsOutcome.Succeeded -> null
            is RunAsOutcome.NotDebuggable ->
                "'$packageName' is not a debuggable build on this device, so its cache cannot " +
                    "be cleared on its own. Install the debug variant, or use Clear Data to " +
                    "wipe everything."
            is RunAsOutcome.Unreachable ->
                "run-as could not reach '$packageName' on this device (${outcome.said}). Cache-only " +
                    "clearing needs a debuggable build installed for the current user."
            is RunAsOutcome.Failed -> when {
                outcome.said.isNotEmpty() -> "Could not clear the cache for '$packageName': ${outcome.said}"
                outcome.status != null ->
                    "Could not clear the cache for '$packageName': rm exited with " +
                        "${outcome.status} and said nothing."
                else ->
                    "Could not clear the cache for '$packageName': the device reported no exit " +
                        "status, so run-as does not appear to be usable here."
            }
        }
}

private const val TIMEOUT_SECONDS = 15L

/**
 * Deletes the app's internal `cache/` and `code_cache/` in one shell round trip.
 *
 * One implementation for both entry points — the panel button and the MCP tool — so the two
 * cannot drift on which directories they touch or on what counts as success.
 *
 * @throws IllegalStateException when `run-as` refuses or the `rm` reports a non-zero status,
 *   carrying what the device said.
 */
internal fun IDevice.clearAppCacheOrThrow(packageName: String): String {
    val output = ShellOutputReceiver()
    executeShellCommand(AppCacheShell.clearCommand(packageName), output, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    AppCacheShell.failureMessage(packageName, output.toString())?.let { error(it) }

    return "Cleared cache and code_cache for $packageName."
}

/** The tool window's Clear Cache. Shares [AppOperations] with `android_clear_app_cache`. */
class ClearAppCacheCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        AppOperations(device).clearCache(p)
    }
}
