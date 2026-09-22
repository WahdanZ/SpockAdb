package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.AppOperations

/**
 * The tool window's Uninstall. Shares [AppOperations] with `android_uninstall_app`.
 *
 * This used to discard what `uninstallPackage` returned, so a device that refused — a device
 * owner, a system app, another user still holding the package — reported "uninstalled" and
 * left the app on screen. The operation now raises what ADB said.
 */
class UninstallAppCommand : Command<String, Unit> {
    override fun execute(p: String, project: Project, device: IDevice) = AppOperations(device).uninstall(p)
}
