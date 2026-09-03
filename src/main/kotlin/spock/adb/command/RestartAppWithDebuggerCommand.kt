package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.compat.DebuggerSupport
import spock.adb.forceKillApp
import spock.adb.getDefaultActivityForApplication
import spock.adb.isAppInstall
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

                        DebuggerSupport.attach(project, device, p)
                    }
                    else -> throw Exception("No Default Activity Found")
                }
            }
            else -> throw Exception("Application $p not installed")
        }
}
