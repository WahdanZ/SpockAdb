package spock.adb.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import spock.adb.SpockAdbService
import spock.adb.command.GetApplicationIDCommand
import spock.adb.device.ConnectedDevice

/**
 * Base for Spock ADB actions that need a device, and optionally an application.
 *
 * Two rules this exists to enforce:
 *
 *  - **Never act on an unexpected device.** With several devices attached, an action runs
 *    against the one selected in the tool window; it does not guess.
 *  - **A disabled action explains itself.** The presentation description carries the reason
 *    ("No Android device connected"), which the IDE shows in Find Action and on hover, so a
 *    greyed-out entry is never a mystery.
 *
 * `update()` runs on a background thread ([ActionUpdateThread.BGT]) because resolving the
 * device list and the application ID both touch ADB and the project model.
 */
abstract class DeviceAwareAction(
    /** When true the action is disabled unless an application ID can be resolved. */
    private val requiresApplication: Boolean = true,
) : AnAction() {

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun update(event: AnActionEvent) {
        val presentation = event.presentation
        val project = event.project

        if (!isAvailable()) {
            presentation.isEnabledAndVisible = false
            return
        }
        if (project == null) {
            presentation.isEnabled = false
            presentation.description = "$baseDescription — no project is open"
            return
        }

        val reason = disabledReason(project)
        presentation.isEnabled = reason == null
        presentation.description = reason?.let { "$baseDescription — $it" } ?: baseDescription
    }

    final override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        SpockAdbService.getInstance(project).controller.connectedDevices { devices ->
            val usable = devices.filter { it.info.isUsable }
            when {
                usable.isEmpty() -> notify(project, "No Android device is ready.")
                usable.size == 1 -> perform(project, usable.single())
                else -> chooseDevice(project, usable)
            }
        }
    }

    /** Null when the action can run; otherwise the reason it cannot, in plain words. */
    private fun disabledReason(project: Project): String? {
        val devices = runCatching { lastKnownDevices(project) }.getOrNull() ?: return null

        return when {
            devices.isEmpty() -> "no Android device connected"

            devices.none { it.info.isUsable } -> devices.joinToString(prefix = "no device is ready: ") {
                "${it.info.displayName} is ${it.info.state.label}"
            }

            requiresApplication && applicationId(project) == null ->
                "no application ID could be resolved from this project"

            else -> null
        }
    }

    protected fun applicationId(project: Project): String? =
        runCatching { GetApplicationIDCommand.resolve(project) }.getOrNull()

    /** Cached device view; `update()` must stay cheap and must not start ADB. */
    private fun lastKnownDevices(project: Project): List<ConnectedDevice> =
        SpockAdbService.getInstance(project).lastKnownDevices()

    private fun chooseDevice(project: Project, devices: List<ConnectedDevice>) {
        val names = devices.map { it.info.label() }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names)
            .setTitle("Run on Which Device?")
            .setItemChosenCallback { chosen ->
                devices.getOrNull(names.indexOf(chosen))?.let { perform(project, it) }
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun notify(project: Project, message: String) =
        spock.adb.notification.CommonNotifier.showNotifier(
            project = project,
            content = message,
            type = com.intellij.notification.NotificationType.WARNING,
        )

    /** Text used when the action is enabled; suffixed with the reason when it is not. */
    /** Lets an action hide itself entirely on IDEs that cannot support it. */
    protected open fun isAvailable(): Boolean = true

    protected abstract val baseDescription: String

    protected abstract fun perform(project: Project, device: ConnectedDevice)
}
