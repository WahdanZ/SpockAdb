package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.InspectionOperations
import spock.adb.models.BackStackData

/** The tool window's Activity Stack. Shares [InspectionOperations] with `android_get_activity_stack`. */
class GetBackStackCommand : Command<Any, List<BackStackData>> {
    override fun execute(p: Any, project: Project, device: IDevice): List<BackStackData> =
        InspectionOperations(device).activityStack()
}
