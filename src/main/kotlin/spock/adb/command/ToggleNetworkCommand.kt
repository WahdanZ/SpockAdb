package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import spock.adb.ShellOutputReceiver
import spock.adb.getNetworkState

class ToggleNetworkCommand : Command<Network, String> {

    override fun execute(p: Network, project: Project, device: IDevice): String {

        return when (device.getNetworkState(p)) {
            NetworkState.DISABLED -> {
                setNetworkState(device, p, NetworkState.ENABLED)
                "Enabled ${p.name.toLowerCase().capitalize()} network"
            }
            NetworkState.ENABLED -> {
                setNetworkState(device, p, NetworkState.DISABLED)
                "Disabled ${p.name.toLowerCase().capitalize()} network"
            }
        }
    }

    private fun setNetworkState(device: IDevice, network: Network, networkState: NetworkState) {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "svc ${network.networkChangeIdentifier} ${getSettingChangeValue(networkState)}",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    }
}

private fun getSettingChangeValue(networkState: NetworkState): String =
    when (networkState) {
        NetworkState.ENABLED -> "enable"
        NetworkState.DISABLED -> "disable"
    }

enum class Network(val networkSettingIdentifier: String, val networkChangeIdentifier: String) {
    WIFI("wifi_on", "wifi"),
    MOBILE("mobile_data", "data")
}

enum class NetworkState(val state: String) {
    ENABLED("1"),
    DISABLED("0");

    companion object {
        private val map = values().associateBy(NetworkState::state)
        fun getState(value: String) = map[value] ?: DISABLED
    }
}
