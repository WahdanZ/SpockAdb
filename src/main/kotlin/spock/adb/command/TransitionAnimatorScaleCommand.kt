package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import spock.adb.ShellQuote
import java.util.concurrent.TimeUnit

class TransitionAnimatorScaleCommand : Command<String, String> {

    override fun execute(p: String, project: Project, device: IDevice): String {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "settings put global transition_animation_scale ${ShellQuote.quote(p)}",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )

        return "Set Transition Animator Scale to $p"
    }
}
