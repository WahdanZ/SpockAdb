package spock.adb

import com.android.ddmlib.IDevice
import spock.adb.command.GetApplicationPermission
import spock.adb.command.HttpProxy
import spock.adb.command.Network
import spock.adb.command.NetworkState
import spock.adb.device.ConnectedDevice
import spock.adb.premission.ListItem

/**
 * What an action did, for the line the tool window keeps on screen.
 *
 * @param elapsedMs how long it took, or null when the action reported outside a timed run.
 */
data class ActionResult(val message: String, val ok: Boolean, val elapsedMs: Long?)

interface AdbController {
    fun refresh()

    /**
     * The app every app action acts on.
     *
     * Set from the tool window's header. Null falls back to the open project's app module,
     * which is what every action resolved for itself before there was anywhere to choose one.
     */
    var selectedApp: String?

    /** Called on the EDT after every action, with what it did. */
    fun onResult(listener: (ActionResult) -> Unit)

    /** Reads the device list once, with metadata resolved. [block] is invoked on the EDT. */
    fun connectedDevices(block: (devices: List<ConnectedDevice>) -> Unit)

    /**
     * Subscribes to the device list: [block] is invoked on the EDT with the current devices
     * and again on every connect, disconnect or state change. Multiple observers are
     * supported — the tool window and the project service both subscribe — and each is
     * notified. One-shot callers should use [connectedDevices] instead of subscribing.
     */
    fun observeDevices(block: (devices: List<ConnectedDevice>) -> Unit)
    fun currentBackStack(device: IDevice)
    fun currentApplicationBackStack(device: IDevice)
    fun currentActivity(device: IDevice)
    fun currentFragment(device: IDevice)
    fun forceKillApp(device: IDevice)
    fun testProcessDeath(device: IDevice)
    fun restartApp(device: IDevice)
    fun restartAppWithDebugger(device: IDevice)
    fun clearAppData(device: IDevice)
    fun clearAppDataAndRestart(device: IDevice)

    /**
     * Clears only the app's internal `cache/` and `code_cache/`, leaving shared preferences,
     * databases and files intact. Goes through `run-as`, so it needs a debuggable build.
     */
    fun clearAppCache(device: IDevice)
    fun uninstallApp(device: IDevice)
    fun getApplicationPermissions(device: IDevice, block: (devices: List<ListItem>) -> Unit)
    fun grantOrRevokeAllPermissions(device: IDevice, permissionOperation: GetApplicationPermission.PermissionOperation)
    fun revokePermission(device: IDevice, listItem: ListItem)
    fun grantPermission(device: IDevice, listItem: ListItem)
    fun connectDeviceOverIp(ip: String)
    fun enableDisableDontKeepActivities(device: IDevice)
    fun enableDisableShowTaps(device: IDevice)
    fun enableDisableShowLayoutBounds(device: IDevice)
    fun setWindowAnimatorScale(scale: String, device: IDevice)
    fun setTransitionAnimatorScale(scale: String, device: IDevice)
    fun setAnimatorDurationScale(scale: String, device: IDevice)

    /**
     * Toggles [network], then calls [onDone] on the EDT.
     *
     * The callback is what lets the row that shows the state read the device back rather than
     * assume the toggle took — `svc wifi` exits 0 whether or not the device honoured it.
     */
    fun toggleNetwork(device: IDevice, network: Network, onDone: () -> Unit = {})

    /** Whether [network] is on, off, or could not be asked. Answered on the EDT. */
    fun networkState(device: IDevice, network: Network, block: (state: Result<NetworkState>) -> Unit)
    fun inputOnDevice(input: String, device: IDevice)
    fun openDeveloperOptions(device: IDevice)
    fun openDeepLink(input: String, device: IDevice)

    /** Points the device at a debugging proxy. [onDone] runs on the EDT afterwards, whether or not it took. */
    fun setHttpProxy(proxy: HttpProxy, device: IDevice, onDone: () -> Unit)

    /** Restores direct connections. [onDone] runs on the EDT afterwards, whether or not it took. */
    fun clearHttpProxy(device: IDevice, onDone: () -> Unit)

    /**
     * Reads the device proxy. [block] is invoked on the EDT with null when no proxy is set,
     * or with a failure when the device could not be read.
     */
    fun currentHttpProxy(device: IDevice, block: (read: Result<HttpProxy?>) -> Unit)
}
