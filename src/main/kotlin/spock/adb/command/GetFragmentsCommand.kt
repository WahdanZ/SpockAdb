package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.InspectionOperations
import spock.adb.models.FragmentData

/** The tool window's Current Fragment. Shares [InspectionOperations] with `android_get_current_fragments`. */
class GetFragmentsCommand : Command<String, List<FragmentData>> {
    override fun execute(p: String, project: Project, device: IDevice): List<FragmentData> =
        InspectionOperations(device).fragments(p)
}
