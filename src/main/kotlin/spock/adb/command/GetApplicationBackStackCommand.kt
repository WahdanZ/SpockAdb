package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.InspectionOperations
import spock.adb.models.ActivityData

/**
 * The selected app's own activities and their fragments, for the tool window's popup.
 *
 * No agent tool reads this today; it is in [InspectionOperations] with its siblings so that
 * when one does, it has the same implementation to reach for.
 */
class GetApplicationBackStackCommand : Command<String, List<ActivityData>> {
    override fun execute(p: String, project: Project, device: IDevice): List<ActivityData> =
        InspectionOperations(device).applicationBackStack(p)
}
