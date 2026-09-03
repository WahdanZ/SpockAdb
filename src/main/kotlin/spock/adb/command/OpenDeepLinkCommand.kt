package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

class OpenDeepLinkCommand : Command<String, String> {

    override fun execute(p: String, project: Project, device: IDevice): String {
        require(p.isNotBlank()) { "Enter a deep link URI first." }

        // Previously interpolated inside double quotes, which do not suppress command
        // substitution: a URI containing $(...) or a backtick ran shell on the device.
        device.executeShellCommand(
            "am start -a android.intent.action.VIEW -d ${ShellQuote.quote(p)}",
            ShellOutputReceiver(),
            15L,
            TimeUnit.SECONDS,
        )
        return "Opened deep link $p"
    }
}
