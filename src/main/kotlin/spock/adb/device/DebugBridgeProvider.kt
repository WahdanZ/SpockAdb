package spock.adb.device

import com.android.ddmlib.AndroidDebugBridge
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.android.sdk.AndroidSdkUtils

/**
 * Resolves the ADB bridge from any thread.
 *
 * `AndroidSdkUtils.getDebugBridge(Project)` opens with
 * `ApplicationManager.getApplication().assertIsDispatchThread()` — it drives a
 * `ProgressManager` task while ADB boots, so it is designed to be called on the EDT and
 * throws anywhere else. Calling it from a pooled thread made every device lookup fail, which
 * is why the device dropdown came up empty.
 *
 * Two paths:
 *
 *  - **Fast path.** Once ADB is running the bridge is a process-wide singleton.
 *    `AndroidDebugBridge.getBridge()` reads it without blocking and without an EDT
 *    requirement, which is what almost every call needs — including the device-change
 *    callbacks that arrive on ddmlib threads.
 *  - **Slow path.** Before ADB has started there is nothing to return, so initialisation is
 *    delegated to `AndroidSdkUtils` on the EDT, where it is legal.
 */
class DebugBridgeProvider(private val project: Project) : () -> AndroidDebugBridge? {

    private val log = Logger.getInstance(DebugBridgeProvider::class.java)

    override fun invoke(): AndroidDebugBridge? {
        connectedBridge()?.let { return it }
        return initialiseOnEdt()
    }

    private fun connectedBridge(): AndroidDebugBridge? =
        runCatching { AndroidDebugBridge.getBridge() }
            .getOrNull()
            ?.takeIf { runCatching { it.isConnected }.getOrDefault(false) }

    private fun initialiseOnEdt(): AndroidDebugBridge? {
        val application = ApplicationManager.getApplication()

        if (application.isDispatchThread) {
            return runCatching { AndroidSdkUtils.getDebugBridge(project) }
                .onFailure { log.warn("Could not start ADB", it) }
                .getOrNull()
        }

        var bridge: AndroidDebugBridge? = null
        application.invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            bridge = runCatching { AndroidSdkUtils.getDebugBridge(project) }
                .onFailure { log.warn("Could not start ADB", it) }
                .getOrNull()
        }
        return bridge
    }
}
