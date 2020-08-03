package spock.adb

import com.android.ddmlib.IDevice
import java.util.concurrent.TimeUnit
import spock.adb.command.DontKeepActivitiesState
import spock.adb.command.ShowLayoutBoundsState
import spock.adb.command.ShowTapsState

fun IDevice.killApp(applicationID: String?, seconds: Long) {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("am force-stop $applicationID", shellOutputReceiver, seconds, TimeUnit.SECONDS)
}

fun IDevice.isAppInstall(applicationID: String?): Boolean {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("pm list packages $applicationID", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return !shellOutputReceiver.toString().isEmpty()
}

fun IDevice.startActivity(activity: String) {
    executeShellCommand("am start -n $activity", ShellOutputReceiver(), 15L, TimeUnit.SECONDS)
}

fun IDevice.clearAppData(applicationID: String?, seconds: Long) {
    executeShellCommand("pm clear $applicationID", ShellOutputReceiver(), seconds, TimeUnit.SECONDS)
}

fun IDevice.getDefaultActivityForApplication(packageName: String?): String {
    val outputReceiver = ShellOutputReceiver()
    if (isNougatOrAbove())
        executeShellCommand(
            "cmd package resolve-activity --brief $packageName | tail -n 1",
            outputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    else {
        executeShellCommand(
            "pm dump $packageName | grep -B 10 category\\.LAUNCHER | grep -o '[^ ]*/[^ ]*' | tail -n 1",
            outputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    }
    return outputReceiver.toString()
}

fun IDevice.isMarshmallow() = this.version.apiLevel >= 23
fun IDevice.isNougatOrAbove() = this.version.apiLevel >= 24

fun IDevice.areDontKeepActivitiesEnabled(): DontKeepActivitiesState {
    val outputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get global always_finish_activities", outputReceiver, 15L, TimeUnit.SECONDS)

    return DontKeepActivitiesState.getState(outputReceiver.toString())
}

fun IDevice.areShowTapsEnabled(): ShowTapsState {
    val outputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get system show_touches", outputReceiver, 15L, TimeUnit.SECONDS)

    return ShowTapsState.getState(outputReceiver.toString())
}

fun IDevice.areShowLayoutBoundsEnabled(): ShowLayoutBoundsState {
    val outputReceiver = ShellOutputReceiver()
    executeShellCommand("getprop debug.layout", outputReceiver, 15L, TimeUnit.SECONDS)

    return ShowLayoutBoundsState.getState(outputReceiver.toString())
}

fun IDevice.refreshUi() {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("service call activity 1599295570", shellOutputReceiver, 15L, TimeUnit.SECONDS)
}

fun IDevice.getWindowAnimatorScale(): String {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get global window_animation_scale", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return shellOutputReceiver.toString()
}

fun IDevice.getTransitionAnimationScale(): String {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get global transition_animation_scale", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return shellOutputReceiver.toString()
}

fun IDevice.getAnimatorDurationScale(): String {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get global animator_duration_scale", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return shellOutputReceiver.toString()
}
