package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

import spock.adb.*
import spock.adb.debugger.Debugger
import java.util.concurrent.TimeUnit

class RestartAppWithDebuggerCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) =
        when {
            device.isAppInstall(p) -> {
                device.forceKillApp(p, 15L)
                val activity = device.getDefaultActivityForApplication(p)

                when {
                    activity.isNotEmpty() -> {
                        device.executeShellCommand(
                            "am start -D -n $activity",
                            ShellOutputReceiver(),
                            15L,
                            TimeUnit.SECONDS
                        )

                        Debugger(project, device, p).attach()
                    }
                    else -> throw Exception("No Default Activity Found")
                }
            }
            else -> throw Exception("Application $p not installed")
        }
}
