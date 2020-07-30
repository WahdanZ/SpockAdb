package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.areShowTapsEnabled
import java.util.concurrent.TimeUnit

class EnableDisableShowTapsCommand : Command<Any, String> {

    override fun execute(p: Any, project: Project, device: IDevice): String {

        return when (device.areShowTapsEnabled()) {
            ShowTapsState.DISABLED -> {
                setShowTapsState(device, ShowTapsState.ENABLED)
                "Enabled show taps"
            }
            ShowTapsState.ENABLED -> {
                setShowTapsState(device, ShowTapsState.DISABLED)
                "Disabled show taps"
            }
        }
    }

    private fun setShowTapsState(device: IDevice, state: ShowTapsState) {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "settings put system show_touches ${state.state}",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    }
}

enum class ShowTapsState(val state: String) {
    ENABLED("1"),
    DISABLED("0");

    companion object {
        private val map = values().associateBy(ShowTapsState::state)
        fun getState(value: String) = map[value] ?: DISABLED
    }
}
