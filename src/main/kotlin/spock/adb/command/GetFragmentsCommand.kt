package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.models.FragmentData
import spock.adb.parser.FragmentDumpParser
import java.util.concurrent.TimeUnit

class GetFragmentsCommand : Command<String, List<FragmentData>> {

    override fun execute(p: String, project: Project, device: IDevice): List<FragmentData> {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand("dumpsys activity top", shellOutputReceiver, 15L, TimeUnit.SECONDS)
        return FragmentDumpParser.parse(shellOutputReceiver.toString())
    }
}
