package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project

class WindowAnimatorScaleCommand : Command<String, AnimationScaleWrite> {

    override fun execute(p: String, project: Project, device: IDevice): AnimationScaleWrite =
        device.setAnimationScale(AnimationScale.WINDOW, p)
}
