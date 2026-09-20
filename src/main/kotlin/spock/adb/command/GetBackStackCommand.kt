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
                // `mResumedActivity` comes along with the stack so the popup can mark the task
                // the user is actually looking at; without it the top of the dump is only a
                // good guess, and it is wrong whenever a second display is involved.
                device.executeShellCommand(
                    "dumpsys activity activities | grep -E 'Hist|mResumedActivity'",
                    shellOutputReceiver,
                    15L,
                    TimeUnit.SECONDS,
                )
                BackStackParser.parseHistory(shellOutputReceiver.toString())
            }
        }
    }
}
