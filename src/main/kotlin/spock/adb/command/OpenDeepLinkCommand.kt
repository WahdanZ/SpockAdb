package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class OpenDeepLinkCommand : Command<String, AmStartResult> {

    override fun execute(p: String, project: Project, device: IDevice): AmStartResult {
        require(p.isNotBlank()) { "Enter a deep link URI first." }

        // The output is the only place the device says whether anything opened — see [AmStart].
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(
            AmStart.command(p),
            receiver,
            15L,
            TimeUnit.SECONDS,
        )
        return AmStartResult.parse(p, receiver.toString())
    }
}
