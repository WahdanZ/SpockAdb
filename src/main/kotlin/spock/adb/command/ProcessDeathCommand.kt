package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.*
import java.util.concurrent.TimeUnit

class ProcessDeathCommand : Command<String, Unit> {

    override fun execute(p: String, project: Project, device: IDevice) {
        if (device.isAppInstall(p)) {
            sendAppToBackgroundIfInForeground(device, p)

            Thread.sleep(2500L) //If we don't add this delay, the following commands executes without the app
            // being on the background thus not working.

            killAppProcess(device, p)

            startApplication(device, p)
        } else {
            throw Exception("Application $p not installed")
        }
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

    private fun startApplication(device: IDevice, p: String) {
        val activity = device.getDefaultActivityForApplication(p)
        when {
            activity.isNotEmpty() -> device.startActivity(activity)
            else -> throw Exception("No Default Activity Found")
        }
    }
}
