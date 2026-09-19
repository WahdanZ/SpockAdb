package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.models.FragmentData
import spock.adb.parser.FragmentDumpParser
import java.util.concurrent.TimeUnit

/**
 * The fragments the selected app currently has added.
 *
 * Dumps the app by name rather than `dumpsys activity top`, for two reasons. On Android 13 and
 * later `top` reports no fragment state at all — the activity is there, its FragmentManager is
 * not — so Current fragment answered "no fragments" for every app that had them. And `top` is
 * whatever is in the foreground, which need not be the app chosen in the tool window: with
 * another app in front it reported that app's fragments, or nothing, without saying so.
 */
class GetFragmentsCommand : Command<String, List<FragmentData>> {

    override fun execute(p: String, project: Project, device: IDevice): List<FragmentData> {
        ShellQuote.requireValidComponent(p, "Package name")
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "dumpsys activity ${ShellQuote.quote(p)}",
            shellOutputReceiver,
            TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        return FragmentDumpParser.parse(shellOutputReceiver.toString())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
