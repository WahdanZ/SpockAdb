package spock.adb.device

/**
 * What a tool call acts on: an Android device reached over ADB, or an iOS simulator reached
 * through `simctl`.
 *
 * Tools written for one platform keep asking for theirs — Android tools still call
 * [spock.adb.mcp.tools.ToolContext.requireDevice] and get a [ConnectedDevice]. What needs to
 * name *either* is the code around a call: the destructive-action dialog, and Diagnose, which
 * reports on whichever target it was given. Not called `Target`, which Kotlin already imports as
 * an annotation.
 */
sealed interface DeviceTarget {

    /** The serial or UDID, as an agent passes it back to select this target. */
    val id: String

    /** How a dialog names it to the developer: "Pixel 8 (emulator-5554)". */
    val label: String

    data class Android(val device: ConnectedDevice) : DeviceTarget {
        override val id: String get() = device.serialNumber
        override val label: String get() = device.info.describe()
    }

    /** A booted iOS simulator. Physical iOS devices are not targets. */
    data class IosSimulator(val udid: String, val name: String, val runtime: String) : DeviceTarget {
        override val id: String get() = udid
        override val label: String get() = "$name ($runtime simulator)"
    }
}
