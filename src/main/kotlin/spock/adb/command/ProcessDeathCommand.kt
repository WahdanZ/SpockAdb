package spock.adb.command

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ops.AppOperations
import spock.adb.device.ops.ProcessDeath

/**
 * The tool window's Process Death action. The work is [AppOperations.simulateProcessDeath],
 * which `android_simulate_process_death` also runs, so both kill and relaunch the same way.
 */
class ProcessDeathCommand : Command<String, ProcessDeath> {

    override fun execute(p: String, project: Project, device: IDevice): ProcessDeath =
        AppOperations(device).simulateProcessDeath(p)
}
