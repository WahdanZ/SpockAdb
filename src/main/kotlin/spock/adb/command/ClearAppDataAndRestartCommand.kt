package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.AppOperations

/**
 * The tool window's Clear Data and Restart, which is how a first run is reproduced.
 *
 * No agent tool does this today; it is here rather than in the controller so that when one
 * exists it has the same implementation to reach for as the button.
 */
class ClearAppDataAndRestartCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        AppOperations(device).clearDataAndRestart(p)
    }
}
