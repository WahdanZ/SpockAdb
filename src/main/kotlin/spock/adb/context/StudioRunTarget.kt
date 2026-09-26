package spock.adb.context

import com.android.ddmlib.IDevice
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * The devices Android Studio's run-target selector names, for "follow Android Studio's device".
 *
 * The run target is a platform [ExecutionTarget]; Android Studio's is an `AndroidExecutionTarget`,
 * whose `getRunningDevices()` returns the chosen devices that are booted. That class has moved
 * package between releases, so it is reached by method name rather than by type: an IDE whose
 * target has no such method — IntelliJ IDEA with no Android run configuration, or a future
 * rename — gives no devices, and the selection simply stays where the developer put it.
 */
internal object StudioRunTarget {

    private val log = Logger.getInstance(StudioRunTarget::class.java)

    /** Serial numbers of the booted devices the run target names. May touch ADB: not on the EDT. */
    fun runningSerials(project: Project): List<String> = runCatching {
        serialsOf(ExecutionTargetManager.getActiveTarget(project))
    }.onFailure { log.debug("Could not read Android Studio's run target", it) }.getOrDefault(emptyList())

    /** Separate from [runningSerials] so the reflection can be tested without an IDE. */
    fun serialsOf(target: Any): List<String> {
        val method = target.javaClass.methods
            .firstOrNull { it.name == RUNNING_DEVICES && it.parameterCount == 0 }
            ?: return emptyList()
        val devices = method.invoke(target) as? Collection<*> ?: return emptyList()
        return devices.mapNotNull { (it as? IDevice)?.serialNumber }
    }

    private const val RUNNING_DEVICES = "getRunningDevices"
}
