package spock.adb.backgroundwork

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import spock.adb.command.DeviceConditionTracker

/**
 * Leaves no device in forced Doze, with an overridden battery, or with an app in a forced bucket
 * once the last project closes — which is also what happens when the IDE quits.
 *
 * Synchronous, under a modal progress dialog. It used to be a pooled task started from the tab's
 * dispose, and quitting the IDE exited before the task ran: the device was left in forced Doze,
 * which is the one outcome this exists to prevent. `projectClosing` runs before the project goes
 * and blocks it until it returns, on quit as on close.
 */
class DeviceConditionsCloseListener : ProjectManagerListener {

    override fun projectClosing(project: Project) {
        if (!isLastProject(ProjectManager.getInstance().openProjects.size)) return
        if (!DeviceConditionTracker.hasOnlineChanges()) return

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { DeviceConditionTracker.resetAllOnline() },
            "Resetting Device Conditions Spock Changed",
            false,
            project,
        )
    }

    internal companion object {
        /** The closing project is still counted as open while it closes. */
        fun isLastProject(openProjects: Int): Boolean = openProjects <= 1
    }
}
