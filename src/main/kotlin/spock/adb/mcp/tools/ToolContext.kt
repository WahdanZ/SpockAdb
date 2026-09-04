package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import spock.adb.device.ConnectedDevice

/**
 * Everything a tool needs to reach a device, without knowing how it was resolved.
 *
 * Device selection is session state: an agent calls `android_select_device` once and every
 * later call targets it. Tools therefore ask for "the device" rather than carrying a serial
 * through every signature, but may still override it per call.
 */
interface ToolContext {

    /**
     * The project whose Android module supplies the application ID, if one can be chosen.
     *
     * Null both when nothing is open and when several projects are open and none has been
     * selected. Prefer [requireProject] where the difference matters: a null cannot tell the
     * agent which of the two problems it has, and they have different fixes.
     */
    val project: Project?

    /** All devices ADB currently reports, with metadata already resolved. */
    fun devices(): List<ConnectedDevice>

    /**
     * The device this call targets.
     *
     * @param serialOverride explicit serial from the tool arguments, if the caller gave one.
     * @throws IllegalStateException with an actionable message when no device can be chosen,
     *   so the agent is told to attach a device or call `android_select_device` rather than
     *   receiving a null it has to guess about.
     */
    fun requireDevice(serialOverride: String? = null): ConnectedDevice

    /** Persists the agent's device choice for subsequent calls. */
    fun selectDevice(serial: String): ConnectedDevice

    /**
     * The project this call is about, or an explanation of why one could not be chosen.
     *
     * @throws IllegalStateException with a message the agent can act on — open a project, or
     *   call `android_select_project` and name one of the projects the message lists.
     */
    fun requireProject(): Project = project ?: error(
        "No project is open, which this tool needs to run. Open the Android project in the " +
            "IDE and let Gradle sync finish.",
    )

    /**
     * Pins the project later calls resolve against, the way [selectDevice] pins the device.
     *
     * @return the name of the project that was selected.
     * @throws IllegalStateException when no open project matches [name].
     */
    fun selectProject(name: String): String

    /**
     * Asks the developer to approve a [ToolSafety.DESTRUCTIVE] call.
     *
     * Returns false when declined. Implementations must block until the developer answers
     * and must never default to true — an unattended IDE denies rather than approves.
     */
    fun confirmDestructive(toolName: String, summary: String, device: ConnectedDevice): Boolean

    /** The application ID of the open project's app module, when it can be resolved. */
    fun projectApplicationId(): String?
}

/** Convenience for the many tools that only need the ddmlib handle. */
fun ToolContext.requireIDevice(serialOverride: String? = null): IDevice =
    requireDevice(serialOverride).device
