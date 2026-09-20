package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.isAppInstall
import spock.adb.isMarshmallow
import spock.adb.premission.ListItem
import java.util.concurrent.TimeUnit

/**
 * The app's runtime permissions, as the device itself classifies them.
 *
 * This used to filter what `dumpsys` reported against a list of permission names written into
 * the plugin — the dangerous permissions as they stood in Android 6. Every runtime permission
 * added since was silently dropped: `POST_NOTIFICATIONS`, the `READ_MEDIA_*` family, the
 * Android 12 Bluetooth permissions, `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`. On
 * this emulator that is fifteen of the thirty-two permissions Chrome actually holds, so the
 * dialog showed half the list and Grant all granted half of it.
 *
 * The filtering is gone. `dumpsys package` has a `runtime permissions:` section, which is the
 * device saying which of this app's permissions are runtime ones — by definition current for
 * whatever Android it is running.
 */
class GetApplicationPermission : Command<String, List<ListItem>> {

    override fun execute(p: String, project: Project, device: IDevice): List<ListItem> {
        check(device.isMarshmallow()) {
            "Device API level is below Marshmallow. Runtime permissions are not supported on this device."
        }
        check(device.isAppInstall(p)) { "Application $p not installed" }

        val receiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys package ${ShellQuote.quote(p)}",
            receiver,
            TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return parse(receiver.toString())
    }

    enum class PermissionOperation(val operationResult: String) {
        GRANT("granted"),
        REVOKE("revoked"),
    }

    companion object {
        private const val TIMEOUT_SECONDS = 15L
        private const val RUNTIME_MARKER = "runtime permissions:"
        private const val GRANTED = ": granted="

        /**
         * Reads the `runtime permissions:` block, sorted by name.
         *
         * A device with more than one user prints the block once per user. Only the first —
         * user 0, the one everything else in this plugin acts on — is read, so a work profile
         * does not report the same permission twice with two different answers.
         */
        fun parse(dumpsys: String): List<ListItem> {
            val found = linkedMapOf<String, Boolean>()
            var inside = false

            dumpsys.lineSequence().map { it.trim() }.forEach { line ->
                when {
                    line == RUNTIME_MARKER -> inside = true
                    // Any other section heading, or the next user, ends the block.
                    !inside -> Unit
                    line.endsWith("permissions:") || line.startsWith("User ") -> inside = false
                    else -> entry(line)?.let { (name, granted) -> found.putIfAbsent(name, granted) }
                }
            }
            return found.map { (name, granted) -> ListItem(name, granted) }.sortedBy { it.name }
        }

        /** `android.permission.CAMERA: granted=true, flags=[ ... ]`, or null for anything else. */
        private fun entry(line: String): Pair<String, Boolean>? {
            val at = line.indexOf(GRANTED)
            if (at <= 0) return null
            val name = line.take(at)
            // A permission name is one token with a package-like shape; the flags that follow
            // contain spaces and brackets, and a wrapped line would otherwise look like a name.
            if ('.' !in name || name.any { it.isWhitespace() }) return null
            return name to line.substring(at + GRANTED.length).startsWith("true")
        }
    }
}
