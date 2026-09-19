package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import spock.adb.isAppInstall
import spock.adb.premission.ListItem
import java.util.concurrent.TimeUnit

class RevokePermissionCommand : Command2<String, ListItem, Unit> {
    override fun execute(p: String, p2: ListItem, project: Project, device: IDevice) {
        check(device.isAppInstall(p)) { "Application $p is not installed on this device" }
        // `pm revoke` prints nothing when it works and an exception when it does not, and sets
        // no exit status either way — so the output is the only thing that can tell them apart.
        val receiver = ShellOutputReceiver()
        device.executeShellCommand(
            "pm revoke ${ShellQuote.quote(p)} ${ShellQuote.quote(p2.name)}",
            receiver,
            15L,
            TimeUnit.SECONDS,
        )
        PermissionChange.failureOf(receiver.toString())?.let { error("${p2.name}: $it") }
    }
}
