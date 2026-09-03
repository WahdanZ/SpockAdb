package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

class InputOnDeviceCommand : Command<String, String> {

    override fun execute(p: String, project: Project, device: IDevice): String {
        // Previously "input text '$p'": a single quote in the text closed the literal and
        // the rest was executed as shell on the device.
        device.executeShellCommand(
            "input text ${ShellQuote.quote(p)}",
            ShellOutputReceiver(),
            15L,
            TimeUnit.SECONDS,
        )
        return "Input on device $p"
    }
}
