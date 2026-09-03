package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.areDontKeepActivitiesEnabled
import java.util.concurrent.TimeUnit

/**
 * Toggles the "Don't keep activities" developer setting.
 *
 * The checkbox for this existed in the tool window and displayed the current state, but was
 * never given an action listener: clicking it moved the tick and did nothing to the device,
 * then silently reverted the next time the panel refreshed.
 */
class EnableDisableDontKeepActivitiesCommand : Command<Any, String> {

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }

    override fun execute(p: Any, project: Project, device: IDevice): String =
        when (device.areDontKeepActivitiesEnabled()) {
            DontKeepActivitiesState.DISABLED -> {
                device.setState(DontKeepActivitiesState.ENABLED)
                "Enabled Don't Keep Activities"
            }
            DontKeepActivitiesState.ENABLED -> {
                device.setState(DontKeepActivitiesState.DISABLED)
                "Disabled Don't Keep Activities"
            }
        }

    private fun IDevice.setState(state: DontKeepActivitiesState) {
        executeShellCommand(
            "settings put global always_finish_activities ${state.state}",
            ShellOutputReceiver(),
            TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
    }
}
