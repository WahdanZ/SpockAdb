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
 */
internal object AppCacheShell {

    /**
     * Relative on purpose. An absolute `/data/data/<pkg>/...` would work only for user 0 and
     * puts a path outside the app's sandbox one typo away from the `rm`.
     */
    val CACHE_DIRS = listOf("cache", "code_cache")

    fun clearCommand(packageName: String): String =
        runAs(packageName, "rm -rf ${CACHE_DIRS.joinToString(" ") { "./$it" }}")

    /** A silent `rm -rf` proves nothing, so the directories are read back afterwards. */
    fun verifyCommand(packageName: String): String =
        runAs(packageName, "ls -A ${CACHE_DIRS.joinToString(" ")} 2>/dev/null")

    /**
     * `null` when the device said nothing, which is what a successful `rm -rf` looks like.
     *
     * Anything else is a refusal. The two shapes `run-as` actually produces get an actionable
     * sentence; everything else is quoted verbatim rather than flattened into "failed".
     */
    fun failureMessage(packageName: String, output: String): String? {
        val said = output.trim()
        if (said.isEmpty()) return null

        val lowered = said.lowercase()
        return when {
            lowered.contains("not debuggable") ->
                "'$packageName' is not a debuggable build on this device, so its cache cannot " +
                    "be cleared on its own. Install the debug variant, or use Clear Data to " +
                    "wipe everything."
            lowered.contains("unknown") || lowered.contains("not found") ->
                "run-as could not reach '$packageName' on this device ($said). Cache-only " +
                    "clearing needs a debuggable build installed for the current user."
            else -> "Could not clear the cache for '$packageName': $said"
        }
    }

    /** What the readback still found. Empty means the cache is gone. */
    fun leftovers(output: String): List<String> =
        output.lines().map(String::trim).filter(String::isNotEmpty)

    private fun runAs(packageName: String, script: String): String =
        "run-as ${ShellQuote.quote(packageName)} sh -c ${ShellQuote.quote(script)}"
}

private const val TIMEOUT_SECONDS = 15L
private const val MAX_REPORTED_LEFTOVERS = 5

/**
 * Deletes the app's internal `cache/` and `code_cache/`, and verifies they are gone.
 *
 * One implementation for both entry points — the panel button and the MCP tool — so the two
 * cannot drift on which directories they touch or on what counts as success.
 *
 * @throws IllegalStateException when `run-as` refuses, or when the readback still finds
 *   something, carrying what the device said.
 */
fun IDevice.clearAppCacheOrThrow(packageName: String): String {
    val clearOutput = ShellOutputReceiver()
    executeShellCommand(AppCacheShell.clearCommand(packageName), clearOutput, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    AppCacheShell.failureMessage(packageName, clearOutput.toString())?.let { error(it) }

    val readback = ShellOutputReceiver()
    executeShellCommand(AppCacheShell.verifyCommand(packageName), readback, TIMEOUT_SECONDS, TimeUnit.SECONDS)
    val leftovers = AppCacheShell.leftovers(readback.toString())
    if (leftovers.isNotEmpty()) {
        error(
            "The cache for '$packageName' was not fully cleared; still present: " +
                leftovers.take(MAX_REPORTED_LEFTOVERS).joinToString(", "),
        )
    }

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
