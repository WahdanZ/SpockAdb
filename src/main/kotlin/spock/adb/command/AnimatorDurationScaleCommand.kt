package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.ShellOutputReceiver
import java.util.concurrent.TimeUnit

class AnimatorDurationScaleCommand : Command<String, String> {

    companion object {
        fun getAnimatorDurationScaleIndex(scale: String?): String = scale ?: "0.0"
    }

    override fun execute(p: String, project: Project, device: IDevice): String {
        val shellOutputReceiver = ShellOutputReceiver()
        device.executeShellCommand(
            "settings put global animator_duration_scale $p",
            shellOutputReceiver,
            15L,
            TimeUnit.SECONDS
        )

        return "Set Animator Duration Scale to $p"
    }
}
