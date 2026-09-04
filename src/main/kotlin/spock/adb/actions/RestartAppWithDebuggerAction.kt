package spock.adb.actions

import com.intellij.openapi.project.Project
import spock.adb.SpockAdbService
import spock.adb.compat.DebuggerSupport
import spock.adb.device.ConnectedDevice

class RestartAppWithDebuggerAction : DeviceAwareAction() {

    override val baseDescription = "Relaunch the app and attach the debugger"

    override fun perform(project: Project, device: ConnectedDevice) =
        SpockAdbService.getInstance(project).controller.restartAppWithDebugger(device.device)

    /** Hidden where the IDE does not ship the Android Studio execution tooling. */
    override fun isAvailable(): Boolean = DebuggerSupport.isAvailable
}
