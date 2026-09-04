package spock.adb

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-scoped owner of the ADB controller.
 *
 * Previously every entry point built its own `AdbControllerImp`: the tool window made one,
 * and each menu action made another on every invocation. Each instance registered a global
 * `AndroidDebugBridge` device-change listener, and the action path disposed its controller
 * immediately after starting asynchronous work.
 *
 * A single instance per project gives the controller a lifetime that matches the project,
 * registers exactly one device-change listener, and lets callers share it without worrying
 * about disposal.
 */
@Service(Service.Level.PROJECT)
class SpockAdbService(project: Project) : Disposable {

    private val delegate = AdbControllerImp(project)

    val controller: AdbController get() = delegate

    @Volatile
    private var cachedDevices: List<spock.adb.device.ConnectedDevice> = emptyList()

    init {
        // Actions call update() constantly; it must never trigger an ADB round trip or start
        // the bridge. Observing keeps a cheap snapshot for them to read.
        delegate.observeDevices { cachedDevices = it }
    }

    /** Devices as of the last ADB update. Safe to call from update(); never blocks. */
    fun lastKnownDevices(): List<spock.adb.device.ConnectedDevice> = cachedDevices

    override fun dispose() = delegate.dispose()

    companion object {
        /**
         * Deliberately not the inline `project.service<T>()` extension: it inlines a call to
         * `ServicesKt.serviceNotFoundError`, which does not exist before 2023.3, so the
         * plugin would fail with NoSuchMethodError on the oldest supported builds. Caught by
         * Plugin Verifier against AI-231; see docs/COMPATIBILITY.md.
         */
        fun getInstance(project: Project): SpockAdbService =
            project.getService(SpockAdbService::class.java)
    }
}
