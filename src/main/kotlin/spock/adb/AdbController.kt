package spock.adb

import com.android.ddmlib.IDevice
import spock.adb.premission.PermissionListItem

interface AdbController {
    fun  refresh()
    fun connectedDevices(block: (devices:List<IDevice>) -> Unit,error:(message:String)->Unit)
    fun currentBackStack(device: IDevice, success: (message: String) -> Unit, error: (message: String) -> Unit)
    fun currentActivity(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun currentFragment(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun forceKillApp(device: IDevice, success:(message:String)->Unit, error:(message:String)->Unit)
    fun restartApp(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun clearAppData(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun getApplicationPermissions(device: IDevice,block: (devices:List<PermissionListItem>) -> Unit,error:(message:String)->Unit)
    fun revokePermission(device: IDevice, permissionListItem: PermissionListItem, success:(message:String)->Unit, error:(message:String)->Unit)
    fun grantPermission(device: IDevice, permissionListItem: PermissionListItem, success:(message:String)->Unit, error:(message:String)->Unit)
    fun connectDeviceOverIp(ip:String, success:(message:String)->Unit, error:(message:String)->Unit)
    fun enableDisableDontKeepActivities(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun enableDisableShowTaps(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun enableDisableShowLayoutBounds(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun setWindowAnimatorScale(scale: String, device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun setTransitionAnimatorScale(scale: String, device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun setAnimatorDurationScale(scale: String, device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
}
