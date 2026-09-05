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
    /** The agent's project choice, when it has made one. Session state, like the device. */
    private val selectedProject: AtomicReference<String?> = AtomicReference(null),
    private val openProjects: () -> List<Project> = { allOpenProjects() },
) : ToolContext {

    override val project: Project?
        get() = (resolveProject() as? ProjectResolution.Outcome.Resolved)?.project

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

    /**
     * Fails with the reason rather than a null.
     *
     * "Nothing is open" and "several are open, say which" need different things from the
     * agent, and returning null for both taught it the wrong fix for one of them.
     */
    override fun requireProject(): Project = when (val outcome = resolveProject()) {
        is ProjectResolution.Outcome.Resolved -> outcome.project

        ProjectResolution.Outcome.None -> error(
            "No project is open, which this tool needs to run. Open the Android project in " +
                "the IDE and let Gradle sync finish.",
        )

        is ProjectResolution.Outcome.Ambiguous -> error(
            "Several projects are open, so it is ambiguous which app this call is about: " +
                outcome.names.joinToString() +
                ". Call android_select_project first, passing one of those exactly as written.",
        )
    }

    override fun selectProject(name: String): String {
        val open = openProjects()
        check(open.isNotEmpty()) { "No project is open." }
        val label = labellerFor(open)

        val match = ProjectResolution.select(open, name, ::keysOf, label) ?: error(
            "No open project is called '$name'. Open: " +
                open.joinToString { label(it) } +
                ". A project may be named by its name, its path, or exactly as listed here.",
        )

        // Remembered by path, not by name. Two checkouts of one repository are both called
        // "app", and a selection stored as "app" resolves back to whichever the IDE lists
        // first — so selecting the fork by its path would silently keep targeting the
        // original, and every project-dependent tool would answer about the wrong app.
        selectedProject.set(match.basePath ?: match.name)
        return label(match)
    }

    /** Labels that separate two open projects sharing a name. */
    private fun labellerFor(candidates: List<Project>): (Project) -> String =
        ProjectResolution.labeller(candidates, nameOf = { it.name }, pathOf = { it.basePath })

    private fun resolveProject(): ProjectResolution.Outcome<Project> {
        // One snapshot: a project opened or closed between the two reads would label the
        // candidates against a different set than the one being resolved, and the labels are
        // what the caller is then told to choose from.
        val open = openProjects()
        return ProjectResolution.resolve(
            candidates = open,
            selectedKey = selectedProject.get(),
            keysOf = { keysOf(it) },
            labelOf = labellerFor(open),
        )
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
        // Any open frame will do: this dialog is about a device operation, not about a
        // project, so refusing to ask merely because two projects are open would deny a call
        // the developer would have approved.
        val currentProject = project ?: openProjects().firstOrNull() ?: return false
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
        /** Disposed projects are gone, not candidates. */
        fun allOpenProjects(): List<Project> =
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }

        /** Everything a project answers to, so a selection can name either. */
        fun keysOf(project: Project): List<String> = listOfNotNull(project.name, project.basePath)
    }
}
