package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.isAppInstall
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
        runAs(packageName, "rm -rf ${CACHE_DIRS.joinToString(" ") { "./$it" }}; echo rc=$?")

    /**
     * `null` when the device reported `rc=0`, which is the `rm` saying it succeeded.
     *
     * Everything else is a failure. `run-as` that refuses never starts the shell, so the
     * status line is missing entirely — the two shapes it actually produces get an actionable
     * sentence, and anything else is quoted verbatim rather than flattened into "failed".
     */
    fun failureMessage(packageName: String, output: String): String? {
        val said = output.trim()
        val lines = said.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val tail = lines.lastOrNull()
        val status = tail?.let { EXIT_STATUS.matchEntire(it) }
        // Whatever the device printed before the status line: rm's own diagnostics, or the
        // refusal from run-as when the status line never arrived.
        val noise = if (status != null) lines.dropLast(1).joinToString("\n").trim() else said

        // The echo runs only if run-as handed the script to a shell at all, so rc=0 is proof
        // the rm ran and succeeded — regardless of anything else printed on the way.
        if (status != null && status.groupValues[1] == "0") return null

        return when {
            noise.contains("not debuggable", ignoreCase = true) ->
                "'$packageName' is not a debuggable build on this device, so its cache cannot " +
                    "be cleared on its own. Install the debug variant, or use Clear Data to " +
                    "wipe everything."
            noise.contains("unknown", ignoreCase = true) || noise.contains("not found", ignoreCase = true) ->
                "run-as could not reach '$packageName' on this device ($noise). Cache-only " +
                    "clearing needs a debuggable build installed for the current user."
            noise.isNotEmpty() -> "Could not clear the cache for '$packageName': $noise"
            status != null ->
                "Could not clear the cache for '$packageName': rm exited with " +
                    "${status.groupValues[1]} and said nothing."
            else ->
                "Could not clear the cache for '$packageName': the device reported no exit " +
                    "status, so run-as does not appear to be usable here."
        }
    }

    /** Anchored at the end: the app's own output may well contain something that looks like this. */
    private val EXIT_STATUS = Regex("""rc=(-?\d+)\s*$""")

    private fun runAs(packageName: String, script: String): String =
        "run-as ${ShellQuote.quote(packageName)} sh -c ${ShellQuote.quote(script)}"
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

class ClearAppCacheCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        if (device.isAppInstall(p)) {
            device.clearAppCacheOrThrow(p)
        } else {
            error("Application $p not installed")
        }
    }
}
