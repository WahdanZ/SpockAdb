package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.isAppInstall
import spock.adb.isMarshmallow
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
 *
 * Each entry also keeps the `USER_SET` and `USER_FIXED` flags, so a permission the system will
 * no longer prompt for can be told apart from one that was simply never granted.
 */
class GetApplicationPermission : Command<String, List<RuntimePermission>> {

    override fun execute(p: String, project: Project, device: IDevice): List<RuntimePermission> {
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
        private const val FLAGS = "flags=["
        private val FLAG_SEPARATORS = Regex("[|\\s]+")

        /**
         * Reads the `runtime permissions:` block, sorted by name.
         *
         * A device with more than one user prints the block once per user. Only the first —
         * user 0, the one everything else in this plugin acts on — is read, so a work profile
         * does not report the same permission twice with two different answers.
         *
         * Only `USER_SET` and `USER_FIXED` are read out of `flags=[ ... ]`, matched as whole
         * tokens — `USER_SENSITIVE_WHEN_DENIED` is not `USER_SET`. A line without flags has neither.
         */
        fun parse(dumpsys: String): List<RuntimePermission> {
            val found = linkedMapOf<String, RuntimePermission>()
            var inside = false
            // Set when the first block ends, so a permission only a later user holds is not added.
            var done = false

            dumpsys.lineSequence().map { it.trim() }.forEach { line ->
                when {
                    done -> Unit
                    line == RUNTIME_MARKER -> inside = true
                    // Any other section heading, or the next user, ends the block.
                    !inside -> Unit
                    line.endsWith("permissions:") || line.startsWith("User ") -> {
                        inside = false
                        done = true
                    }
                    else -> entry(line)?.let { found.putIfAbsent(it.name, it) }
                }
            }
            return found.values.sortedBy { it.name }
        }

        /** `android.permission.CAMERA: granted=true, flags=[ ... ]`, or null for anything else. */
        private fun entry(line: String): RuntimePermission? {
            val at = line.indexOf(GRANTED)
            if (at <= 0) return null
            val name = line.take(at)
            // A permission name is one token with a package-like shape; the flags that follow
            // contain spaces and brackets, and a wrapped line would otherwise look like a name.
            if ('.' !in name || name.any { it.isWhitespace() }) return null
            val rest = line.substring(at + GRANTED.length)
            val flags = flags(rest)
            return RuntimePermission(
                name = name,
                granted = rest.startsWith("true"),
                userSet = "USER_SET" in flags,
                userFixed = "USER_FIXED" in flags,
            )
        }

        /** The tokens inside `flags=[ ... ]`, or none when the line has no flags. */
        private fun flags(rest: String): Set<String> {
            val open = rest.indexOf(FLAGS)
            if (open < 0) return emptySet()
            val inside = rest.substring(open + FLAGS.length).substringBefore(']')
            return inside.split(FLAG_SEPARATORS).filter { it.isNotEmpty() }.toSet()
        }
    }
}
