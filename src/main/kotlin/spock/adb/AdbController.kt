package spock.adb

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellEnabledDevice
import io.netty.util.concurrent.SucceededFuture

interface AdbController {
    fun connectedDevices(block: (devices:List<IDevice>) -> Unit,error:(message:String)->Unit)
    fun currentActivity(device: IDevice,error:(message:String)->Unit)
    fun currentFragment(device: IDevice,error:(message:String)->Unit)
    fun killApp(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun restartApp(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
    fun clearAppData(device: IDevice,success:(message:String)->Unit,error:(message:String)->Unit)
}