package spock.adb

import com.android.ddmlib.IDevice
import java.util.concurrent.TimeUnit


fun IDevice.killApp(applicationID: String?, seconds: Long) {
    executeShellCommand("am force-stop $applicationID", ShellOutputReceiver(), seconds, TimeUnit.SECONDS)
}

fun IDevice.isAppInstall(applicationID: String?, error: (message: String) -> Unit):Boolean{
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("pm list packages $applicationID", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return if (!shellOutputReceiver.toString().isEmpty()) true
    else{
        error("$applicationID is not installed on $name")
        false
    }
}

 fun IDevice.startActivity(activity: String) {
    executeShellCommand("am start -n $activity", ShellOutputReceiver(),15L, TimeUnit.SECONDS)
}
fun IDevice.clearAppData(applicationID: String?) {
    executeShellCommand("pm clear $applicationID", ShellOutputReceiver(),15L, TimeUnit.SECONDS)
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