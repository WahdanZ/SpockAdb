package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.AndroidSdkUtils

class GetConnectedDevicesCommand : Command<Unit, List<IDevice>> {
    override fun execute(p: Unit, project: Project, device: IDevice): List<IDevice> {
        return AndroidSdkUtils.getDebugBridge(project)?.devices?.toList() as List<IDevice>
    }
}
