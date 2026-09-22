package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.AppOperations

/**
 * The tool window's Force Kill.
 *
 * The behaviour lives in [AppOperations], which `android_stop_app` runs too, so the button
 * and the agent stop an app the same way.
 */
class ForceKillAppCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) = AppOperations(device).stop(p)
}
