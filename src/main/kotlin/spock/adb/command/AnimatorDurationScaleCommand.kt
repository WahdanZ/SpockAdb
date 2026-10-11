package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

class AnimatorDurationScaleCommand : Command<String, AnimationScaleWrite> {

    override fun execute(p: String, project: Project, device: IDevice): AnimationScaleWrite =
        device.setAnimationScale(AnimationScale.DURATION, p)
}
