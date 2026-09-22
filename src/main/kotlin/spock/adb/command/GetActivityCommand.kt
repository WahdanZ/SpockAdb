package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.InspectionOperations

/** The tool window's Current Activity. Shares [InspectionOperations] with `android_get_current_activity`. */
class GetActivityCommand : Command<Any, String?> {
    override fun execute(p: Any, project: Project, device: IDevice): String? =
        InspectionOperations(device).currentActivity()
}
