package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.SCALES
import spock.adb.animationScaleEntry

/** Sets the window, transition and animator-duration scales to one value, each read back. */
class SetAllAnimationScalesCommand : Command<String, AnimationScaleWrites> {

    override fun execute(p: String, project: Project, device: IDevice): AnimationScaleWrites {
        val entry = animationScaleEntry(p, SCALES)
        require(entry != null) { "Unknown animation scale '$p'. Use one of: ${SCALES.joinToString()}." }
        return device.setAllAnimationScales(entry)
    }
}
