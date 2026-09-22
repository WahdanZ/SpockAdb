package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.InspectionOperations

/**
 * The display label of each of the given packages, for the ones a device can prove one for.
 *
 * Best effort by design — see [InspectionOperations.appLabels], which does the work.
 */
class GetAppLabelsCommand : Command<List<String>, Map<String, String>> {
    override fun execute(p: List<String>, project: Project, device: IDevice): Map<String, String> =
        InspectionOperations(device).appLabels(p)
}
