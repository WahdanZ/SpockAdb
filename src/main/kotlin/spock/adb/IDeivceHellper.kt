package spock.adb

import com.android.ddmlib.IDevice
import spock.adb.command.DontKeepActivitiesState
import spock.adb.command.HttpProxy
import spock.adb.command.Network
import spock.adb.command.NetworkState
import spock.adb.command.ShowLayoutBoundsState
import spock.adb.command.ShowTapsState
import java.util.concurrent.TimeUnit

fun IDevice.forceKillApp(applicationID: String?, seconds: Long) {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand(
        "am force-stop ${ShellQuote.quote(applicationID.orEmpty())}",
        shellOutputReceiver,
        seconds,
        TimeUnit.SECONDS,
    )
}

fun IDevice.isAppInstall(applicationID: String?): Boolean {
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand(
        "pm list packages ${ShellQuote.quote(applicationID.orEmpty())}",
        shellOutputReceiver,
        15L,
        TimeUnit.SECONDS,
    )
    return !shellOutputReceiver.toString().isEmpty()
}

fun IDevice.startActivity(activity: String) {
    executeShellCommand(
        "am start -n ${ShellQuote.quote(activity)}",
        ShellOutputReceiver(),
        15L,
        TimeUnit.SECONDS,
    )
}

fun IDevice.clearAppData(applicationID: String?, seconds: Long) {
    executeShellCommand(
        "pm clear ${ShellQuote.quote(applicationID.orEmpty())}",
        ShellOutputReceiver(),
        seconds,
        TimeUnit.SECONDS,
    )
}

fun IDevice.getDefaultActivityForApplication(packageName: String?): String {
    val outputReceiver = ShellOutputReceiver()
    if (isNougatOrAbove()) {
        executeShellCommand(
            "cmd package resolve-activity --brief " +
                "${ShellQuote.quote(packageName.orEmpty())} | tail -n 1",
            outputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    } else {
        executeShellCommand(
            "pm dump ${ShellQuote.quote(packageName.orEmpty())} " +
                "| grep -B 10 category\\.LAUNCHER | grep -o '[^ ]*/[^ ]*' | tail -n 1",
            outputReceiver,
            15L,
            TimeUnit.SECONDS
        )
    }
    return outputReceiver.toString()
}

fun IDevice.isMarshmallow() = (apiLevel() ?: 0) >= 23
fun IDevice.isNougatOrAbove() = (apiLevel() ?: 0) >= 24

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

fun IDevice.isAppInForeground(applicationID: String?):Boolean{
    val shellOutputReceiver = ShellOutputReceiver()
    executeShellCommand("dumpsys activity recents | grep 'Recent #0' | cut -d= -f2 | sed 's| .*||' | cut -d '/' -f1", shellOutputReceiver, 15L, TimeUnit.SECONDS)
    return shellOutputReceiver.toString().equals(applicationID, true)
}

fun IDevice.getNetworkState(network: Network): NetworkState {
    val outputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get global ${network.networkSettingIdentifier}", outputReceiver, 15L, TimeUnit.SECONDS)

    return NetworkState.getState(outputReceiver.toString())
}

/**
 * The device's **API level** (e.g. 33), not its marketing version.
 *
 * The previous implementation read `ro.build.version.release`, which yields the
 * user-visible version string ("13", "8.1.0"). Values like "8.1.0" fail `toIntOrNull()`
 * and were silently treated as "unknown", pushing modern devices down the pre-Honeycomb
 * parsing path. `ro.build.version.sdk` is the API level and is always an integer.
 *
 * ddmlib's cached property table is consulted first since it avoids a shell round trip;
 * an explicit `getprop` is the fallback.
 */
fun IDevice.apiLevel(): Int? {
    // ddmlib caches device properties, so this usually avoids a shell round trip.
    // Read the property directly rather than IDevice.version.apiLevel, which is deprecated.
    runCatching { getProperty("ro.build.version.sdk") }
        .getOrNull()
        ?.trim()
        ?.toIntOrNull()
        ?.let { return it }

    val outputReceiver = ShellOutputReceiver()
    executeShellCommand("getprop ro.build.version.sdk", outputReceiver, 15L, TimeUnit.SECONDS)
    return outputReceiver.toString().trim().toIntOrNull()
}

/**
 * The device's global HTTP proxy, or null when none is set.
 *
 * See [HttpProxy.parse] for why "null", "" and ":0" all mean the same thing here.
 */
fun IDevice.getHttpProxy(): HttpProxy? {
    val outputReceiver = ShellOutputReceiver()
    executeShellCommand("settings get global http_proxy", outputReceiver, 15L, TimeUnit.SECONDS)

    return HttpProxy.parse(outputReceiver.toString())
}

fun IDevice.setHttpProxy(proxy: HttpProxy) {
    executeShellCommand(
        "settings put global http_proxy ${ShellQuote.quote(proxy.toString())}",
        ShellOutputReceiver(),
        15L,
        TimeUnit.SECONDS,
    )
}

/** Android clears the proxy by writing `:0`, not by removing the setting. */
fun IDevice.clearHttpProxy() {
    executeShellCommand(
        "settings put global http_proxy ${ShellQuote.quote(HttpProxy.CLEARED)}",
        ShellOutputReceiver(),
        15L,
        TimeUnit.SECONDS,
    )
}
