package spock.adb.device.ops

import com.android.ddmlib.IDevice
import spock.adb.clearAppData
import spock.adb.command.clearAppCacheOrThrow
import spock.adb.forceKillApp
import spock.adb.getDefaultActivityForApplication
import spock.adb.isAppInstall
import spock.adb.startActivity

/**
 * The app is not on the device, so there is nothing to act on.
 *
 * A distinct type because it is a precondition rather than a device failure: a caller that
 * has to decide something before running — a destructive tool deciding whether to put a
 * confirmation dialog in front of the developer at all — asks [AppOperations.isInstalled]
 * first, and everything else lets the operation refuse.
 */
class AppNotInstalledException(val packageName: String) : IllegalStateException(message(packageName)) {
    companion object {
        /** Also used by the callers that check before they act, so both say the same thing. */
        fun message(packageName: String) = "Package '$packageName' is not installed on this device."
    }
}

/**
 * Launch, stop, restart, clear and uninstall an app on one device.
 *
 * This is the Android half of those actions and nothing else: no Swing, no PSI, no MCP, no
 * notifications. Both entry surfaces sit on top of it — the tool window through the
 * `Command` classes [AdbControllerImp][spock.adb.AdbControllerImp] runs, and agents through
 * the `android_*` tools — so "restart the app" means the same shell commands, the same
 * preconditions and the same failures whichever one asked for it.
 *
 * It was not that before. `android_stop_app` force-stopped a package without first checking
 * it was installed while the tool window's Force Kill did; Uninstall in the tool window
 * ignored what `uninstallPackage` returned and reported every failed uninstall as a success
 * while the tool reported it; and the two paths answered a missing launcher activity with
 * different sentences. Each of those is a one-line difference that nobody chose.
 *
 * Success text is deliberately *not* here. The tool window and an agent phrase what happened
 * for different readers, so each adapter writes its own sentence from what the operation
 * returns; what the operation fixes is the behaviour and the failures.
 *
 * One instance is one action, on one thread. Callers construct it per action — see
 * [isInstalled] for what that buys.
 *
 * @param timeoutSeconds how long to wait for each shell round trip. The default is the value
 *   the tool window has always used; the agent path used a different one for the same
 *   commands, which is exactly the kind of difference this class exists to remove.
 */
class AppOperations(
    private val device: IDevice,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {

    /** Answers already read from the device, so one action asks once. See [isInstalled]. */
    private val installed = mutableMapOf<String, Boolean>()

    /**
     * Whether [packageName] is installed, read from the device once per instance.
     *
     * A destructive caller has to know this *before* it asks the developer to confirm —
     * offering to uninstall an app that is not there is worse than useless — and the
     * operation it then runs checks the same thing. Reading it once means that pair costs
     * one `pm list packages`, not two, and keeps the commands a single action sends
     * identical from both entry paths.
     */
    fun isInstalled(packageName: String): Boolean =
        installed.getOrPut(packageName) { device.isAppInstall(packageName) }

    /**
     * Starts the app's default launcher activity.
     *
     * @return the component that was started, e.g. `com.example/.MainActivity`.
     */
    fun launch(packageName: String): String {
        requireInstalled(packageName)
        return start(packageName)
    }

    /** Force-stops the app. Its data is untouched and it stays in recents. */
    fun stop(packageName: String) {
        requireInstalled(packageName)
        device.forceKillApp(packageName, timeoutSeconds)
    }

    /**
     * Force-stops the app and starts it again, for a cold start.
     *
     * @return the component that was started.
     */
    fun restart(packageName: String): String {
        requireInstalled(packageName)
        device.forceKillApp(packageName, timeoutSeconds)
        return start(packageName)
    }

    /** Deletes everything the app owns: shared preferences, databases and caches. */
    fun clearData(packageName: String) {
        requireInstalled(packageName)
        device.clearAppData(packageName, timeoutSeconds)
    }

    /**
     * Deletes the app's data and starts it again, which is how a first run is reproduced.
     *
     * @return the component that was started.
     */
    fun clearDataAndRestart(packageName: String): String {
        requireInstalled(packageName)
        device.clearAppData(packageName, timeoutSeconds)
        return start(packageName)
    }

    /**
     * Deletes only `cache/` and `code_cache/`, through `run-as` — see
     * [AppCacheShell][spock.adb.command.AppCacheShell] for why never `pm clear --cache-only`.
     *
     * @return what was cleared, in the words the shell step reports.
     * @throws IllegalStateException when `run-as` refuses or the `rm` fails.
     */
    fun clearCache(packageName: String): String {
        requireInstalled(packageName)
        return device.clearAppCacheOrThrow(packageName)
    }

    /**
     * Removes the app and its data from the device.
     *
     * @throws IllegalStateException carrying what ADB said when the uninstall did not take.
     *   A device-owner app, a system package and an app another user still has installed all
     *   refuse here, and reporting that as done is how the tool window used to lie.
     *
     * ADB reports success as no message at all. A blank one is read the same way rather than
     * as a failure nobody can act on, since it would name nothing that went wrong.
     */
    fun uninstall(packageName: String) {
        requireInstalled(packageName)
        device.uninstallPackage(packageName)?.takeIf { it.isNotBlank() }?.let { failure ->
            error("Could not uninstall $packageName: $failure")
        }
        installed[packageName] = false
    }

    private fun requireInstalled(packageName: String) {
        if (!isInstalled(packageName)) throw AppNotInstalledException(packageName)
    }

    /**
     * Resolves the launcher activity and starts it.
     *
     * An app with no launchable activity — a widget-only app, a service, a library test APK —
     * is a refusal that names the package, not the bare "No Default Activity Found" the tool
     * window used to show.
     */
    private fun start(packageName: String): String {
        val activity = device.getDefaultActivityForApplication(packageName)
        if (activity.isBlank()) error("'$packageName' declares no launchable activity.")
        device.startActivity(activity)
        return activity
    }

    companion object {
        /** Seconds to wait for one shell round trip. */
        const val DEFAULT_TIMEOUT_SECONDS = 15L
    }
}
