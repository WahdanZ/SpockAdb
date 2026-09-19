package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.apiLevel
import spock.adb.getNetworkState
import java.util.concurrent.TimeUnit

/**
 * Switches Wi-Fi or mobile data on the device, and checks that it took.
 *
 * `svc` exits 0 whatever happens — including on the devices where it is not allowed to do
 * anything at all. From Android 10 the shell user lost the permission `svc wifi` needs on a
 * good many builds, so the command ran, said nothing, changed nothing, and the plugin
 * announced that Wi-Fi had been switched off. Two changes: Wi-Fi goes through
 * `cmd -w wifi set-wifi-enabled` where the device is new enough to have it, and whatever the
 * route, the setting is read back afterwards and a device that did not move says so.
 */
class ToggleNetworkCommand : Command<Network, String> {

    override fun execute(p: Network, project: Project, device: IDevice): String {
        val before = device.getNetworkState(p)
        val wanted = if (before == NetworkState.ENABLED) NetworkState.DISABLED else NetworkState.ENABLED

        val output = device.setNetworkState(p, wanted)
        val after = device.readBack(p, wanted)

        check(after == wanted) {
            buildString {
                append("${p.label} is still ${after.describe()}: the device did not accept the change.")
                if (output.isNotBlank()) append(" It said: ${output.trim()}")
                if (p == Network.WIFI) {
                    append(
                        " Many builds from Android 10 onwards refuse to let the adb shell switch " +
                            "Wi-Fi; switching it from the device's own settings is then the only way.",
                    )
                }
            }
        }
        return "${wanted.describe().replaceFirstChar(Char::uppercaseChar)} ${p.label}"
    }

    /**
     * Reads the setting back, once the device has had a moment.
     *
     * `svc` returns before the change has necessarily landed in `settings`, and reading too
     * early would report a device that did move as one that did not.
     */
    private fun IDevice.readBack(network: Network, wanted: NetworkState): NetworkState {
        repeat(READ_BACK_ATTEMPTS) { attempt ->
            val state = getNetworkState(network)
            if (state == wanted) return state
            if (attempt < READ_BACK_ATTEMPTS - 1) Thread.sleep(READ_BACK_PAUSE_MS)
        }
        return getNetworkState(network)
    }

    /** @return whatever the shell said, which on a refusal is the only explanation there is. */
    private fun IDevice.setNetworkState(network: Network, state: NetworkState): String {
        val receiver = ShellOutputReceiver()
        executeShellCommand(network.command(this, state), receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return receiver.toString()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val READ_BACK_ATTEMPTS = 3
        const val READ_BACK_PAUSE_MS = 250L
    }
}

/**
 * The shell command that sets [state].
 *
 * `cmd -w wifi set-wifi-enabled` is the supported way in and after Android 11; `svc` is what
 * every older device has. Mobile data has no equivalent that is present across the range, so it
 * keeps `svc data` — and the read-back is what catches a device that refuses it.
 */
internal fun Network.command(device: IDevice, state: NetworkState): String {
    val enabling = state == NetworkState.ENABLED
    val modernWifi = this == Network.WIFI && (device.apiLevel() ?: 0) >= MODERN_WIFI_API
    return when {
        modernWifi -> "cmd -w wifi set-wifi-enabled ${if (enabling) "enabled" else "disabled"}"
        else -> "svc $networkChangeIdentifier ${if (enabling) "enable" else "disable"}"
    }
}

/** Android 11, where `cmd -w wifi set-wifi-enabled` arrived. */
private const val MODERN_WIFI_API = 30

enum class Network(
    val networkSettingIdentifier: String,
    val networkChangeIdentifier: String,
    /** What to call it in a message to the developer. */
    val label: String,
) {
    WIFI("wifi_on", "wifi", "Wi-Fi"),
    MOBILE("mobile_data", "data", "mobile data"),
}

enum class NetworkState(val state: String) {
    ENABLED("1"),
    DISABLED("0"),
    ;

    /** Past tense, because it is only ever said about something that has already happened. */
    fun describe(): String = if (this == ENABLED) "enabled" else "disabled"

    companion object {
        private val map = entries.associateBy(NetworkState::state)

        /** Anything the device did not answer with a plain 1 is treated as off. */
        fun getState(value: String) = map[value.trim()] ?: DISABLED
    }
}
