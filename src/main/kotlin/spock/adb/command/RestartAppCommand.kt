package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.AppOperations

/** The tool window's Restart App. Shares [AppOperations] with `android_restart_app`. */
class RestartAppCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) {
        AppOperations(device).restart(p)
    }
}
