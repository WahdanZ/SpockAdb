package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.AppOperations

/** The tool window's Clear Data. Shares [AppOperations] with `android_clear_app_data`. */
class ClearAppDataCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) = AppOperations(device).clearData(p)
}
