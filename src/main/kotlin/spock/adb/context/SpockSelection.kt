package spock.adb.context

import com.intellij.execution.ExecutionTargetListener
import com.intellij.execution.ExecutionTargetManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import spock.adb.AppSettingService
import spock.adb.LatestRequest
import spock.adb.SpockAdbService
import spock.adb.command.GetApplicationIDCommand
import spock.adb.command.InstalledPackages
import spock.adb.command.ListInstalledPackagesCommand
import spock.adb.device.ConnectedDevice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The device and the app every Spock surface acts on, chosen once for the whole project.
 *
 * This lived in the tool window: its header owned the device dropdown and the app picker, and
 * the shell pushed each choice into every tab. That made the tool window the only place a
 * choice could be made or read — an action run from the keyboard asked "which device?" again,
 * and a second tool window would have needed a second header that could disagree with the
 * first. Here it is a project service, so the tool windows, the status bar and the actions all
 * read the same answer and are told when it changes.
 *
 * Everything is read and written on the EDT, except [snapshot], which is safe to read from an
 * action's background `update()`.
 */
@Service(Service.Level.PROJECT)
class SpockSelection(private val project: Project) : Disposable {

    /** What is selected, and what there was to choose from. */
    data class Snapshot(
        val devices: List<ConnectedDevice> = emptyList(),
        val device: ConnectedDevice? = null,
        /** The apps installed on [device], with the project's own app first. */
        val apps: List<String> = emptyList(),
        /** The open project's application ID, when Gradle has resolved one. */
        val projectApp: String? = null,
        val app: String? = null,
    )

    /** What a notification is about. A listener acts on the parts it shows. */
    enum class Change {
        /** The list of connected devices. */
        DEVICES,

        /** Which device is selected, or a reconnect handed out a new handle for it. */
        DEVICE,

        /** The list of installed apps. */
        APPS,

        /**
         * Which app is selected. Also sent after a different device's apps are read, with the
         * same package: the app is the same name on another device, and its state is not.
         */
        APP,
    }

    fun interface Listener {
        fun selectionChanged(snapshot: Snapshot, changes: Set<Change>)
    }

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Started and answered on the EDT, so a slow read of one device's apps cannot land on another's. */
    private val appLoads = LatestRequest()

    private var disposed = false

    /**
     * The device Android Studio's run target named when last read, connected or not. Its
     * choice is followed when it *changes*, so a device picked here stays picked until the
     * developer picks another one there.
     */
    private var studioSerial: String? = null

    init {
        // The observer is called on the EDT with the current list, and again on every change.
        SpockAdbService.getInstance(project).controller.observeDevices { devices -> onDevices(devices) }
        project.messageBus.connect(this).subscribe(
            ExecutionTargetManager.TOPIC,
            ExecutionTargetListener { readStudioTarget(force = true) },
        )
    }

    /** Whether the device chosen in Android Studio's run-target selector is selected here too. */
    var followsStudio: Boolean
        get() = AppSettingService.getInstance().state.followStudioDevice
        set(value) {
            val service = AppSettingService.getInstance()
            service.loadState(service.state.copy(followStudioDevice = value))
            if (value) readStudioTarget(force = true)
        }

    /**
     * Calls [listener] on every change until [parent] is disposed, and once now with what is
     * already known, so a surface built after the first device arrived does not start blank.
     */
    fun addListener(parent: Disposable, listener: Listener) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        listener.selectionChanged(snapshot, Change.entries.toSet())
    }

    /** Chooses the device with [serial]. A serial that is not connected is ignored. */
    fun selectDevice(serial: String) {
        val device = snapshot.devices.firstOrNull { it.serialNumber == serial } ?: return
        if (device.serialNumber == snapshot.device?.serialNumber) return
        applyDevice(device, keepApp = false, extra = emptySet())
    }

    /**
     * Chooses the app every action and surface uses. Blank is ignored: an app picker being
     * cleared on the way to typing a new name is not a choice.
     */
    fun selectApp(packageName: String) {
        val app = packageName.trim().takeIf { it.isNotEmpty() } ?: return
        if (app == snapshot.app) return
        setApp(app, emptySet())
    }

    /** Reads the device list again, which reads the selected device's apps again with it. */
    fun refresh() {
        SpockAdbService.getInstance(project).controller.refresh()
    }

    override fun dispose() {
        disposed = true
        listeners.clear()
    }

    // ---------------------------------------------------------------- devices

    private fun onDevices(devices: List<ConnectedDevice>) {
        if (disposed) return
        // Matched on serial rather than instance identity: ddmlib hands out a new IDevice after
        // a reconnect, so identity comparison silently reset the selection. Falls back to the
        // serial remembered from the previous session, then to the first device that is actually
        // usable, so the plugin does not default to an offline one.
        val preferred = snapshot.device?.serialNumber ?: persistedSerial()
        val next = SelectionRules.nextDevice(devices, preferred)
        val same = next != null && next.serialNumber == snapshot.device?.serialNumber
        snapshot = snapshot.copy(devices = devices)
        applyDevice(next, keepApp = same, extra = setOf(Change.DEVICES))
        // A device Android Studio was told to run on may have just finished booting.
        readStudioTarget(force = false)
    }

    /**
     * Selects the device Android Studio's run target names, when following it and it changed.
     *
     * @param force follows it even when it is the one read last time: the target was just
     *   chosen again, or following was just switched on.
     */
    private fun readStudioTarget(force: Boolean) {
        if (disposed || !followsStudio) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val serials = StudioRunTarget.runningSerials(project)
            ApplicationManager.getApplication().invokeLater({
                if (disposed || !followsStudio) return@invokeLater
                val named = serials.firstNotNullOfOrNull { serial ->
                    snapshot.devices.firstOrNull { it.serialNumber == serial }
                }
                val changed = force || named?.serialNumber != studioSerial
                studioSerial = named?.serialNumber
                if (named != null && changed && named.serialNumber != snapshot.device?.serialNumber) {
                    applyDevice(named, keepApp = false, extra = emptySet())
                }
            }) { project.isDisposed }
        }
    }

    /**
     * Makes [device] the selected one and reads its apps.
     *
     * @param keepApp keeps the chosen app when the device did not actually change, so a
     *   reconnect or a device-list refresh does not throw away what is being worked on.
     */
    private fun applyDevice(device: ConnectedDevice?, keepApp: Boolean, extra: Set<Change>) {
        snapshot = snapshot.copy(device = device)
        remember(device)
        notify(extra + Change.DEVICE)
        loadApps(device, keepApp)
    }

    private fun persistedSerial(): String? =
        AppSettingService.getInstance().state.selectedDevice?.takeIf { it.isNotBlank() }

    private fun remember(device: ConnectedDevice?) {
        val service = AppSettingService.getInstance()
        val current = service.state
        val serial = device?.serialNumber
        if (current.selectedDevice != serial) service.loadState(current.copy(selectedDevice = serial))
    }

    // ---------------------------------------------------------------- apps

    /**
     * Reads the apps installed on [device] and selects the project's app.
     *
     * The project's app is resolved each time rather than once, because Gradle sync often
     * finishes after the first device arrives, and an app left unset is what made the developer
     * type it.
     */
    private fun loadApps(device: ConnectedDevice?, keepApp: Boolean) {
        val request = appLoads.begin()
        val projectApp = runCatching { GetApplicationIDCommand.resolve(project) }.getOrNull()
        if (device == null) {
            snapshot = snapshot.copy(apps = emptyList(), projectApp = projectApp)
            notify(setOf(Change.APPS))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val installed = runCatching { ListInstalledPackagesCommand().execute(project, device.device) }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || !appLoads.isLatest(request)) return@invokeLater
                // The project's app is listed even when it is not installed, so the reason it has
                // no files is said once it is chosen, rather than the choice being left blank.
                val apps = InstalledPackages.choices(installed.getOrDefault(emptyList()), projectApp)
                snapshot = snapshot.copy(apps = apps, projectApp = projectApp)
                val app = SelectionRules.nextApp(current = snapshot.app, projectApp = projectApp, keep = keepApp)
                if (app == null) notify(setOf(Change.APPS)) else setApp(app, setOf(Change.APPS))
            }) { project.isDisposed }
        }
    }

    private fun setApp(app: String, extra: Set<Change>) {
        snapshot = snapshot.copy(app = app)
        SpockAdbService.getInstance(project).controller.selectedApp = app
        notify(extra + Change.APP)
    }

    private fun notify(changes: Set<Change>) {
        if (disposed) return
        val now = snapshot
        listeners.forEach { it.selectionChanged(now, changes) }
    }

    companion object {
        fun getInstance(project: Project): SpockSelection = project.getService(SpockSelection::class.java)
    }
}

/** The choices [SpockSelection] makes on its own, as functions that can be tested without ADB. */
internal object SelectionRules {

    /** The device to select from [devices]: [preferred] if connected, else the first usable one. */
    fun nextDevice(devices: List<ConnectedDevice>, preferred: String?): ConnectedDevice? =
        devices.firstOrNull { it.serialNumber == preferred }
            ?: devices.firstOrNull { it.info.isUsable }
            ?: devices.firstOrNull()

    /**
     * The app to select after a device's apps are read: the one already chosen when it is kept,
     * else the project's, else whatever was chosen before, so a project with no app module does
     * not lose the package the developer typed.
     */
    fun nextApp(current: String?, projectApp: String?, keep: Boolean): String? =
        if (keep && current != null) current else projectApp ?: current
}
