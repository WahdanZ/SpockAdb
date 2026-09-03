package spock.adb.device

import com.android.ddmlib.IDevice

/**
 * Builds a [DeviceInfo] from a ddmlib handle.
 *
 * `IDevice.getProperty` can block on a device that is slow or offline, so this must only be
 * called from a background thread. Every read is guarded: an offline or unauthorised device
 * answers some properties and not others, and a partially described device is far more
 * useful than an exception.
 */
object DeviceInfoReader {

    private const val PROP_MODEL = "ro.product.model"
    private const val PROP_MANUFACTURER = "ro.product.manufacturer"
    private const val PROP_RELEASE = "ro.build.version.release"
    private const val PROP_SDK = "ro.build.version.sdk"
    private const val PROP_ABI = "ro.product.cpu.abi"
    private const val PROP_AVD_NAME = "ro.boot.qemu.avd_name"
    private const val PROP_AVD_NAME_LEGACY = "ro.kernel.qemu.avd_name"

    fun read(device: IDevice): DeviceInfo {
        val serial = runCatching { device.serialNumber }.getOrNull().orEmpty()
        val state = device.deviceState()

        // An offline or unauthorised device will not answer property queries; skip them
        // rather than blocking on each one in turn.
        if (state != DeviceState.ONLINE) {
            return DeviceInfo.unknown(serial).copy(
                state = state,
                isEmulator = runCatching { device.isEmulator }.getOrDefault(false),
                model = device.avdNameOrEmpty(),
            )
        }

        return DeviceInfo(
            serialNumber = serial,
            model = device.prop(PROP_MODEL).ifBlank { device.avdNameOrEmpty() },
            manufacturer = device.prop(PROP_MANUFACTURER),
            androidVersion = device.prop(PROP_RELEASE),
            apiLevel = device.prop(PROP_SDK).toIntOrNull(),
            abi = device.prop(PROP_ABI),
            isEmulator = runCatching { device.isEmulator }.getOrDefault(false),
            state = state,
        )
    }

    private fun IDevice.prop(name: String): String =
        runCatching { getProperty(name) }.getOrNull()?.trim().orEmpty()

    /**
     * Best-effort emulator name.
     *
     * `IDevice.getAvdName()` is deprecated in current ddmlib (its replacement returns a
     * future), so the AVD name is read from the emulator's own properties instead, newest
     * property first, falling back to the ddmlib display name.
     */
    private fun IDevice.avdNameOrEmpty(): String =
        prop(PROP_AVD_NAME).ifBlank { prop(PROP_AVD_NAME_LEGACY) }
            .ifBlank { runCatching { name }.getOrNull().orEmpty() }

    private fun IDevice.deviceState(): DeviceState = runCatching {
        when (state) {
            IDevice.DeviceState.ONLINE -> DeviceState.ONLINE
            IDevice.DeviceState.OFFLINE -> DeviceState.OFFLINE
            IDevice.DeviceState.UNAUTHORIZED -> DeviceState.UNAUTHORIZED
            IDevice.DeviceState.BOOTLOADER -> DeviceState.BOOTLOADER
            IDevice.DeviceState.DISCONNECTED -> DeviceState.DISCONNECTED
            else -> DeviceState.UNKNOWN
        }
    }.getOrDefault(DeviceState.UNKNOWN)
}
