package spock.adb

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder

/**
 * Confirmation prompts for operations that destroy state on the device.
 *
 * Uninstalling, wiping app data and revoking every permission were all a single click with
 * no confirmation, directly beside non-destructive actions such as "Current Activity". With
 * several devices attached it was also not visible which one a click would hit — the target
 * is now named in the prompt.
 */
object DestructiveActionConfirmation {

    fun confirmUninstall(project: Project, device: IDevice): Boolean = ask(
        project,
        title = "Uninstall Application",
        message = "Uninstall the app from ${device.describe()}?\n\n" +
            "This removes the application and all of its data from the device.",
        yesText = "Uninstall",
    )

    fun confirmClearData(project: Project, device: IDevice, andRestart: Boolean): Boolean = ask(
        project,
        title = if (andRestart) "Clear App Data and Restart" else "Clear App Data",
        message = "Clear all data for the app on ${device.describe()}?\n\n" +
            "Shared preferences, databases and cached files are deleted. This cannot be undone.",
        yesText = "Clear Data",
    )

    fun confirmRevokeAllPermissions(project: Project, device: IDevice): Boolean = ask(
        project,
        title = "Revoke All Permissions",
        message = "Revoke every runtime permission for the app on ${device.describe()}?\n\n" +
            "The app may crash or misbehave until permissions are granted again.",
        yesText = "Revoke All",
    )

    private fun ask(project: Project, title: String, message: String, yesText: String): Boolean =
        MessageDialogBuilder
            .yesNo(title, message)
            .yesText(yesText)
            .noText("Cancel")
            .asWarning()
            .ask(project)

    /** Device model plus serial, so the prompt is unambiguous with several devices attached. */
    private fun IDevice.describe(): String {
        val model = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (model != null && model != serialNumber) "$model ($serialNumber)" else serialNumber
    }
}
