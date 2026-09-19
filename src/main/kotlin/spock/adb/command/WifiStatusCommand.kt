package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.getNetworkState
import java.util.concurrent.TimeUnit

/**
 * Whether Wi-Fi is on, and the network it is on.
 *
 * `settings get global wifi_on` answers the first half and nothing else, so a device sitting on
 * an enabled radio with no connection read exactly like one on the office network — which is the
 * state a developer is usually trying to tell apart when they look at all.
 */
data class WifiStatus(val enabled: Boolean, val ssid: String?) {

    /** `Connected (AndroidWifi)`, `On`, or `Off` — what the row says about the radio. */
    fun describe(): String = when {
        ssid != null -> "Connected ($ssid)"
        enabled -> "On"
        else -> "Off"
    }

    companion object {
        /**
         * Reads `cmd -w wifi status`.
         *
         * Only the two lines that have kept their wording are read: `Wifi is enabled|disabled`
         * and `Wifi is connected to "<ssid>"`. The `WifiInfo:` dump below them carries the same
         * SSID inside a couple of hundred comma-separated fields that change every release.
         */
        fun parse(output: String): WifiStatus {
            val lines = output.lineSequence().map { it.trim() }.toList()
            val connectedTo = lines.firstOrNull { it.startsWith(CONNECTED_PREFIX) }
                ?.removePrefix(CONNECTED_PREFIX)
                ?.trim()
                ?.trim('"')
                ?.takeIf { it.isNotEmpty() }
            val enabled = lines.any { it.equals(ENABLED, ignoreCase = true) } || connectedTo != null
            return WifiStatus(enabled, connectedTo)
        }

        private const val CONNECTED_PREFIX = "Wifi is connected to"
        private const val ENABLED = "Wifi is enabled"
    }
}

/**
 * Reads the Wi-Fi status, falling back to the setting on a device with no `cmd wifi`.
 *
 * `cmd -w wifi status` arrived with Android 11. Older devices answer nothing useful, and the
 * global setting is all there is: it gives the radio, never the network.
 */
class WifiStatusCommand : Command<Any, WifiStatus> {

    override fun execute(p: Any, project: Project, device: IDevice): WifiStatus {
        val receiver = ShellOutputReceiver()
        device.executeShellCommand("cmd -w wifi status", receiver, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val output = receiver.toString()
        if (output.contains("Wifi is", ignoreCase = true)) return WifiStatus.parse(output)

        return WifiStatus(enabled = device.getNetworkState(Network.WIFI) == NetworkState.ENABLED, ssid = null)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
