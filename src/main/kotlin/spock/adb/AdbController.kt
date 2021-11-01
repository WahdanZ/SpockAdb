package spock.adb

import com.android.ddmlib.IDevice
import spock.adb.command.GetApplicationPermission
import spock.adb.command.Network
import spock.adb.premission.ListItem

interface AdbController {
    fun refresh()
    fun connectedDevices(block: (devices: List<IDevice>) -> Unit)
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
    fun uninstallApp(device: IDevice)
    fun getApplicationPermissions(device: IDevice, block: (devices: List<ListItem>) -> Unit)
    fun grantOrRevokeAllPermissions(device: IDevice, permissionOperation: GetApplicationPermission.PermissionOperation)
    fun revokePermission(device: IDevice, listItem: ListItem)
    fun grantPermission(device: IDevice, listItem: ListItem)
    fun connectDeviceOverIp(ip: String)
    fun enableDisableShowTaps(device: IDevice)
    fun enableDisableShowLayoutBounds(device: IDevice)
    fun setWindowAnimatorScale(scale: String, device: IDevice)
    fun setTransitionAnimatorScale(scale: String, device: IDevice)
    fun setAnimatorDurationScale(scale: String, device: IDevice)
    fun toggleNetwork(device: IDevice, network: Network)
    fun inputOnDevice(input: String, device: IDevice)
    fun openDeveloperOptions(device: IDevice)
}
