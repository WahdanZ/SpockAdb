package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.*
import spock.adb.device.ops.AppNotInstalledException
import spock.adb.device.ops.AppOperations
import java.util.concurrent.TimeUnit

class ProcessDeathCommand : Command<String, Unit> {

    override fun execute(p: String, project: Project, device: IDevice) {
        // The same operations the tool window's other app actions and the agent tools use, so
        // "not installed" and "no launchable activity" read the same here as everywhere else.
        val operations = AppOperations(device)
        if (!operations.isInstalled(p)) throw AppNotInstalledException(p)

        sendAppToBackgroundIfInForeground(device, p)

        Thread.sleep(2500L) //If we don't add this delay, the following commands executes without the app
        // being on the background thus not working.

        killAppProcess(device, p)

        operations.launch(p)
    }

    private fun sendAppToBackgroundIfInForeground(device: IDevice, p: String) {
        if (device.isAppInForeground(p)) {
            // A timeout of 0 means "wait forever" in ddmlib. If the device stopped
            // responding, this hung the pooled thread with no way to recover.
            device.executeShellCommand("input keyevent 3", ShellOutputReceiver(), 15L, TimeUnit.SECONDS)
        }
    }

    private fun killAppProcess(device: IDevice, p: String) =
        device.executeShellCommand(
            "am kill ${ShellQuote.quote(p)}",
            ShellOutputReceiver(),
            15L,
            TimeUnit.SECONDS,
        )
}
