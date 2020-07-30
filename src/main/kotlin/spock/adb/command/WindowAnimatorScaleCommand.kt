package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class WindowAnimatorScaleCommand : Command<String, String> {

    companion object {
        fun getWindowAnimatorScaleIndex(scale: String?): String = scale ?: "0.0"
    }

    override fun execute(p: String, project: Project, device: IDevice): String {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "settings put global window_animation_scale $p",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )

        return "Set Window Animator Scale to $p"
    }
}
