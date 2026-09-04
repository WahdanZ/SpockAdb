package spock.adb.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.wm.WindowManager
import spock.adb.command.GetApplicationIDCommand
import spock.adb.device.ConnectedDevice
import spock.adb.device.DebugBridgeProvider
import spock.adb.device.DeviceInfoReader
import spock.adb.device.DeviceLister
import spock.adb.mcp.tools.ToolContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges MCP tools to the plugin's existing device and project services.
 *
 * The MCP layer deliberately owns no ADB logic: it resolves devices through the same
 * [DeviceLister] and [DebugBridgeProvider] the tool window uses, so device handling has one
 * implementation and one set of failure messages.
 */
class McpToolContext(
    private val selectedSerial: AtomicReference<String?>,
    private val projectProvider: () -> Project? = { defaultProject() },
) : ToolContext {

    override val project: Project? get() = projectProvider()

    private val lister: DeviceLister
        get() {
            val currentProject = project
            val bridge = currentProject?.let { DebugBridgeProvider(it) }
            return DeviceLister(
                devicesSupplier = { bridge?.invoke()?.devices?.toList() },
                readInfo = DeviceInfoReader::read,
            )
        }

    override fun devices(): List<ConnectedDevice> = lister.list()

    override fun requireDevice(serialOverride: String?): ConnectedDevice {
        val devices = devices()
        check(devices.isNotEmpty()) {
            "No Android devices are connected. Attach a device or start an emulator, then retry."
        }

        val wanted = serialOverride ?: selectedSerial.get()
        if (wanted != null) {
            devices.firstOrNull { it.serialNumber == wanted }?.let { return it }
            if (serialOverride != null) {
                error(
                    "No connected device has serial '$serialOverride'. " +
                        "Connected: ${devices.joinToString { it.serialNumber }}.",
                )
            }
        }

        val usable = devices.filter { it.info.isUsable }
        check(usable.isNotEmpty()) {
            "No connected device is ready: " +
                devices.joinToString { "${it.info.displayName} is ${it.info.state.label}" }
        }
        check(usable.size == 1) {
            "Several devices are connected. Call android_select_device first, or pass " +
                "deviceSerial. Connected: ${usable.joinToString { it.serialNumber }}."
        }
        return usable.single()
    }

    override fun selectDevice(serial: String): ConnectedDevice {
        val device = devices().firstOrNull { it.serialNumber == serial }
            ?: error("No connected device has serial '$serial'.")
        selectedSerial.set(serial)
        return device
    }

    override fun projectApplicationId(): String? =
        project?.let { runCatching { GetApplicationIDCommand.resolve(it) }.getOrNull() }

    /**
     * Blocks the calling MCP thread until the developer answers.
     *
     * Defaults to *denied*: an unattended IDE, a disposed project or a failure to show the
     * dialog must never be read as approval. An agent that cannot get an answer is told no.
     */
    override fun confirmDestructive(
        toolName: String,
        summary: String,
        device: ConnectedDevice,
    ): Boolean {
        val currentProject = project ?: return false
        if (currentProject.isDisposed) return false

        var approved = false
        ApplicationManager.getApplication().invokeAndWait {
            if (currentProject.isDisposed) return@invokeAndWait

            // Bring the IDE forward: the request came from another application, so the
            // developer is very likely not looking at this window.
            runCatching { WindowManager.getInstance().getFrame(currentProject)?.toFront() }

            approved = MessageDialogBuilder
                .yesNo(
                    "AI Agent Requests a Destructive Action",
                    "An MCP client wants to run $toolName on ${device.info.describe()}.\n\n" +
                        "$summary\n\nThis cannot be undone.",
                )
                .yesText("Allow once")
                .noText("Deny")
                .asWarning()
                .ask(currentProject)
        }
        return approved
    }

    private companion object {
        /** The single open project, when there is exactly one; otherwise nothing to guess at. */
        fun defaultProject(): Project? =
            ProjectManager.getInstance().openProjects.singleOrNull { !it.isDisposed }
    }
}
