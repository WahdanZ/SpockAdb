package spock.adb.device

/**
 * Everything the UI needs to describe a device, read once on a background thread.
 *
 * The tool window previously showed only `IDevice.name`, which for a physical device is the
 * raw model id and for an emulator is the AVD name. With more than one device attached
 * there was no way to tell an emulator from a handset, an online device from an offline
 * one, or which API level a command was about to run against.
 */
data class DeviceInfo(
    val serialNumber: String,
    val model: String,
    val manufacturer: String,
    /** Marketing version, e.g. "14". Empty when the device did not answer. */
    val androidVersion: String,
    val apiLevel: Int?,
    /** Primary ABI, e.g. "arm64-v8a". Empty when the device did not answer. */
    val abi: String,
    val isEmulator: Boolean,
    val state: DeviceState,
) {

    val isUsable: Boolean get() = state == DeviceState.ONLINE

    /** Human name: manufacturer and model for a handset, the AVD name for an emulator. */
    val displayName: String
        get() = when {
            model.isBlank() -> serialNumber
            manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }

    /**
     * One line for the device dropdown, e.g.
     * `Pixel 7 - Android 14 (API 34) - arm64-v8a`, with the state appended when the device
     * is not ready to accept commands.
     */
    fun label(): String = buildString {
        append(if (isEmulator) "Emulator: " else "Device: ")
        append(displayName)

        val version = androidVersionLabel()
        if (version.isNotEmpty()) append(" - ").append(version)
        if (abi.isNotEmpty()) append(" - ").append(abi)
        if (state != DeviceState.ONLINE) append(" - ").append(state.label)
    }

    fun androidVersionLabel(): String = when {
        androidVersion.isNotBlank() && apiLevel != null -> "Android $androidVersion (API $apiLevel)"
        androidVersion.isNotBlank() -> "Android $androidVersion"
        apiLevel != null -> "API $apiLevel"
        else -> ""
    }

    /** Unambiguous identification for confirmation prompts and notifications. */
    fun describe(): String =
        if (displayName == serialNumber) serialNumber else "$displayName ($serialNumber)"

    companion object {
        /** Used when a device disconnects before its properties could be read. */
        fun unknown(serialNumber: String) = DeviceInfo(
            serialNumber = serialNumber,
            model = "",
            manufacturer = "",
            androidVersion = "",
            apiLevel = null,
            abi = "",
            isEmulator = false,
            state = DeviceState.UNKNOWN,
        )
    }
}

enum class DeviceState(val label: String) {
    ONLINE("online"),
    OFFLINE("offline"),
    UNAUTHORIZED("unauthorized"),
    BOOTLOADER("bootloader"),
    DISCONNECTED("disconnected"),
    UNKNOWN("unknown"),
}
