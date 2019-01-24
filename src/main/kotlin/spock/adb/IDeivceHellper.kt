package spock.adb

import com.android.ddmlib.IDevice
import java.util.concurrent.TimeUnit


fun IDevice.killApp(applicationID: String?, seconds: Long) {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("am force-stop $applicationID", shellOutputReceiver, seconds, TimeUnit.SECONDS)
    shellOutputReceiver
}

fun IDevice.isAppInstall(applicationID: String?):Boolean{
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("pm list packages $applicationID", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return !shellOutputReceiver.toString().isEmpty()
}

 fun IDevice.startActivity(activity: String) {
    executeShellCommand("am start -n $activity", ShellOutputReceiver(),15L, TimeUnit.SECONDS)
}
fun IDevice.clearAppData(applicationID: String?,seconds: Long) {
    executeShellCommand("pm clear $applicationID", ShellOutputReceiver(),seconds, TimeUnit.SECONDS)
}
fun IDevice.getDefaultActivityForApplication(packageName: String?):String {
    val  outputReceiver = ShellOutputReceiver()
    if (isNougatOrAbove())
        executeShellCommand("cmd package resolve-activity --brief $packageName | tail -n 1",outputReceiver,15L, TimeUnit.SECONDS)
    else{
        executeShellCommand("pm dump $packageName | grep -B 10 category\\.LAUNCHER | grep -o '[^ ]*/[^ ]*' | tail -n 1",outputReceiver,15L, TimeUnit.SECONDS)
    }
    return outputReceiver.toString()
}
fun IDevice.isMarshmallow() = this.version.apiLevel >= 23
fun IDevice.isNougatOrAbove() = this.version.apiLevel >= 24