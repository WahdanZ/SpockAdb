package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class InputOnDeviceCommand : Command<String, String> {

    override fun execute(p: String, project: Project, device: IDevice): String {
        device.executeShellCommand("input text '$p'", ShellOutputReceiver(), 15L, TimeUnit.SECONDS)
        return "Input on device $p"
    }
}
