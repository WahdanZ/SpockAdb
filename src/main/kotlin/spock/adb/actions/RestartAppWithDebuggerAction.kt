package spock.adb.actions

import com.android.ddmlib.IDevice
import spock.adb.AdbController

class RestartAppWithDebuggerAction : BaseAction() {
    override fun performAction(controller: AdbController, device: IDevice) {
        controller.restartAppWithDebugger(device)
    }
}