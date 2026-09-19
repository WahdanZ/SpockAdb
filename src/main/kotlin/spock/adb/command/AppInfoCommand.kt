package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

/**
 * What the tool window can say about the selected app without being asked twice.
 *
 * The header names a package and nothing else, so two apps whose names differ by a suffix — the
 * debug build and the release one, or two flavours — look identical. The version and the UID are
 * what tell them apart, and whether it is running is what explains why Force stop did nothing.
 */
data class AppInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    /** The app's Linux UID, which is also what `run-as` and file ownership are about. */
    val uid: String?,
    /** The process id, or null when the app is not running. */
    val pid: String?,
) {
    val isRunning: Boolean get() = pid != null

    /** `1.0 (1)`, or whichever half the device answered with. */
    fun version(): String? = when {
        versionName != null && versionCode != null -> "$versionName ($versionCode)"
        else -> versionName ?: versionCode?.let { "build $it" }
    }

    companion object {
        /**
         * Reads `dumpsys package` and `pidof` output.
         *
         * `dumpsys` prints the fields among a few hundred lines of permission state, so each is
         * found by name rather than by position — the order and the neighbours of these lines
         * have both changed across releases.
         */
        fun parse(packageName: String, dumpsys: String, pidof: String): AppInfo = AppInfo(
            packageName = packageName,
            versionName = dumpsys.field("versionName"),
            versionCode = dumpsys.field("versionCode"),
            // appId is the package's own UID. A bare `uid=` also appears, but under permission
            // entries, where it belongs to the permission's declarer rather than to this app.
            uid = dumpsys.field("appId"),
            pid = pidof.trim().split(Regex("\\s+")).firstOrNull { it.toIntOrNull() != null },
        )

        /** The value of `name=value` on whichever line carries it, or null when none does. */
        private fun String.field(name: String): String? = lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf("$name=")
                if (at < 0) return@mapNotNull null
                line.substring(at + name.length + 1).trim().takeWhile { !it.isWhitespace() }
            }
            .firstOrNull { it.isNotEmpty() }
    }
}

/** Reads [AppInfo] for one package. Two shell round trips, because `dumpsys` has no pid. */
class AppInfoCommand : Command<String, AppInfo> {

    override fun execute(p: String, project: Project, device: IDevice): AppInfo {
        ShellQuote.requireValidComponent(p, "Package name")
        // Both arguments quoted. `requireValidComponent` deliberately allows `$`, for component
        // names, so an unquoted package containing one is expanded by the shell before `pidof`
        // ever sees it — and answers for whatever process that expansion happened to name.
        val quoted = ShellQuote.quote(p)
        return AppInfo.parse(p, device.shell("dumpsys package $quoted"), device.shell("pidof $quoted"))
    }

    private fun IDevice.shell(command: String): String {
        val receiver = ShellOutputReceiver()
        executeShellCommand(command, receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return receiver.toString()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
