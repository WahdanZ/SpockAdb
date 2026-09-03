package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.apiLevel
import spock.adb.models.BackStackData
import spock.adb.parser.BackStackParser
import java.util.concurrent.TimeUnit

class GetBackStackCommand : Command<Any, List<BackStackData>> {

    companion object {
        /** `* Hist #n` lines were introduced in Honeycomb (API 11). */
        const val API_LEVEL_HONEYCOMB = 11
    }

    override fun execute(p: Any, project: Project, device: IDevice): List<BackStackData> {
        val shellOutputReceiver = ShellOutputReceiver()
        val apiLevel = device.apiLevel()

        return when {
            apiLevel != null && apiLevel < API_LEVEL_HONEYCOMB -> {
                device.executeShellCommand(
                    "dumpsys activity activities | sed -En -e '/Running activities/,/Run #0/p'",
                    shellOutputReceiver,
                    15L,
                    TimeUnit.SECONDS,
                )
                BackStackParser.parseLegacy(shellOutputReceiver.toString())
            }
            else -> {
                device.executeShellCommand(
                    "dumpsys activity activities | grep Hist",
                    shellOutputReceiver,
                    15L,
                    TimeUnit.SECONDS,
                )
                BackStackParser.parseHistory(shellOutputReceiver.toString())
            }
        }
    }
}
