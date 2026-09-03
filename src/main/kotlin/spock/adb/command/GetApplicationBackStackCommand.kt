package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.models.ActivityData
import spock.adb.parser.ApplicationBackStackParser
import java.util.concurrent.TimeUnit

class GetApplicationBackStackCommand : Command<String, List<ActivityData>> {

    override fun execute(p: String, project: Project, device: IDevice): List<ActivityData> {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys activity ${ShellQuote.quote(p)}",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS,
        )
        return ApplicationBackStackParser.parse(shellOutputReceiver.toString())
    }
}
