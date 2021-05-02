package spock.adb.actions

import com.android.ddmlib.IDevice
import spock.adb.AdbController

class GetCurrentActivityAction : BaseAction() {
    override fun performAction(controller: AdbController, device: IDevice) {
        controller.currentActivity(device)
    }
}