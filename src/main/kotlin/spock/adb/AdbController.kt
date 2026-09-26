package spock.adb

import com.android.ddmlib.IDevice
import spock.adb.command.AppInfo
import spock.adb.command.GetApplicationPermission
import spock.adb.command.HttpProxy
import spock.adb.command.Network
import spock.adb.command.NetworkState
import spock.adb.command.PushDelivery
import spock.adb.command.PushMessage
import spock.adb.command.ShellAccess
import spock.adb.command.WifiStatus
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

    /**
     * Grants or revokes every runtime permission, then calls [onDone] on the EDT.
     *
     * The callback is what lets a panel showing how many permissions the app holds read that
     * number again: without it the count on screen was the one from before the change.
     */
    fun grantOrRevokeAllPermissions(
        device: IDevice,
        permissionOperation: GetApplicationPermission.PermissionOperation,
        onDone: () -> Unit = {},
    )

    fun revokePermission(device: IDevice, listItem: ListItem, onDone: () -> Unit = {})
    fun grantPermission(device: IDevice, listItem: ListItem, onDone: () -> Unit = {})
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

    /** Whether Wi-Fi is on and which network it is on. Answered on the EDT. */
    fun wifiStatus(device: IDevice, block: (status: Result<WifiStatus>) -> Unit)

    /** The selected app's version, UID and whether it is running. Answered on the EDT. */
    fun appInfo(device: IDevice, block: (info: Result<AppInfo>) -> Unit)

    /**
     * The activity in front and the selected app's fragments, read without opening anything.
     * Answered on the EDT; the fragments are empty when the app cannot be resolved.
     */
    fun screen(device: IDevice, block: (screen: Result<ScreenInfo>) -> Unit)

    /** How many of the app's runtime permissions are granted. Answered on the EDT. */
    fun permissionSummary(device: IDevice, block: (summary: Result<PermissionSummary>) -> Unit)

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

    /**
     * Hands [message] to the selected app's messaging receiver on each of [devices], and
     * reports each device's verdict: one refusal must not hide the others' deliveries.
     * [onDone] runs on the EDT with one [PushDelivery] per device, in the order given.
     */
    fun sendPushMessage(message: PushMessage, devices: List<ConnectedDevice>, onDone: (List<PushDelivery>) -> Unit)

    /** Whether each device can deliver push messages to the selected app, before sending. Answered on the EDT. */
    fun pushShellAccess(devices: List<ConnectedDevice>, block: (Map<ConnectedDevice, ShellAccess>) -> Unit)
}

/**
 * How many of an app's runtime permissions it actually holds.
 *
 * The tab offered Grant all and Revoke all with no way to see what the app had, so the answer
 * to "did that take?" was to open the dialog and read a list.
 */
data class PermissionSummary(val granted: Int, val denied: Int) {
    val total: Int get() = granted + denied

    fun describe(): String = if (total == 0) "No runtime permissions" else "$granted granted / $denied denied"
}

/**
 * What is on screen: the resumed activity's class, and the app's fragments as top-level names.
 *
 * @param activity fully qualified, or null when the device named none.
 */
data class ScreenInfo(val activity: String?, val fragments: List<String>)
