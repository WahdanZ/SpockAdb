package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.areDontKeepActivitiesEnabled
import java.util.concurrent.TimeUnit

class EnableDisableDontKeepActivitiesCommand : Command<Any, String> {

    override fun execute(p: Any, project: Project, device: IDevice): String {

        return when (device.areDontKeepActivitiesEnabled()) {
            DontKeepActivitiesState.DISABLED -> {
                setDontKeepActivitiesState(device, DontKeepActivitiesState.ENABLED)
                "Enabled dont keep activities"
            }
            DontKeepActivitiesState.ENABLED -> {
                setDontKeepActivitiesState(device, DontKeepActivitiesState.DISABLED)
                "Disabled dont keep activities"
            }
        }
    }

    private fun setDontKeepActivitiesState(device: IDevice, state: DontKeepActivitiesState) {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "settings put global always_finish_activities ${state.state}",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    }
}

enum class DontKeepActivitiesState(val state: String) {
    ENABLED("1"),
    DISABLED("0");

    companion object {
        private val map = values().associateBy(DontKeepActivitiesState::state)
        fun getState(value: String) = map[value] ?: DISABLED
    }
}
