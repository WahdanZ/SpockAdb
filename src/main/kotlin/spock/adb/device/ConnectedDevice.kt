package spock.adb.device

import com.android.ddmlib.IDevice

/**
 * Pairs the ddmlib handle used to run commands with the metadata used to describe it.
 *
 * Commands still take an [IDevice]; this only carries the already-resolved [DeviceInfo] so
 * the UI never has to read device properties (a blocking call) on the EDT.
 */
data class ConnectedDevice(
    val device: IDevice,
    val info: DeviceInfo,
) {
    val serialNumber: String get() = info.serialNumber
}
