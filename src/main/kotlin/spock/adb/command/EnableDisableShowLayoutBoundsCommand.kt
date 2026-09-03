package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.areShowLayoutBoundsEnabled
import java.util.concurrent.TimeUnit

class EnableDisableShowLayoutBoundsCommand : Command<Any, String> {

    override fun execute(p: Any, project: Project, device: IDevice): String {

        return when (device.areShowLayoutBoundsEnabled()) {
            ShowLayoutBoundsState.DISABLED -> {
                setShowLayoutBoundsState(device, ShowLayoutBoundsState.ENABLED)
                "Enabled layout bounds"
            }
            ShowLayoutBoundsState.ENABLED -> {
                setShowLayoutBoundsState(device, ShowLayoutBoundsState.DISABLED)
                "Disabled layout bounds"
            }
        }
    }

    private fun setShowLayoutBoundsState(device: IDevice, state: ShowLayoutBoundsState) {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "setprop debug.layout ${state.state}",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    }
}

enum class ShowLayoutBoundsState(val state: String) {
    ENABLED("true"),
    DISABLED("false");

    companion object {
        fun getState(value: String) = if (value.toBoolean()) ENABLED else DISABLED
    }
}
