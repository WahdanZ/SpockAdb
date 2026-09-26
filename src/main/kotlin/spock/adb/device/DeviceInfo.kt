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

    /**
     * The line the device dropdown shows: the name and the Android version, and nothing else.
     *
     * [label] puts the serial-adjacent detail — architecture — on the same line, which in a
     * docked tool window is the part that pushes the name out of view. The detail is not lost:
     * [details] puts it in the dropdown's tooltip, where it is one hover away.
     */
    fun shortLabel(): String = buildString {
        if (isEmulator) append(EMULATOR_PREFIX)
        append(displayName)

        val version = androidVersionLabel(withApiLevel = false)
        if (version.isNotEmpty()) append(SEPARATOR).append(version)
        if (state != DeviceState.ONLINE) append(SEPARATOR).append(state.label)
    }

    /**
     * The fewest words that still tell two devices apart, for the status bar: `Pixel 8 · API 35`,
     * or `Emulator 5554 · API 34`.
     *
     * An emulator's model is the system image's (`sdk_gphone64_arm64`), which names no device
     * and is the same for every emulator, so the console port from its serial names it instead.
     */
    fun compactLabel(): String = buildString {
        val port = serialNumber.removePrefix(EMULATOR_SERIAL_PREFIX).takeIf { isEmulator && it != serialNumber }
        append(
            when {
                port != null -> "Emulator $port"
                isEmulator -> "Emulator"
                model.isNotBlank() -> model
                else -> serialNumber
            },
        )
        apiLevel?.let { append(SEPARATOR).append("API ").append(it) }
        if (state != DeviceState.ONLINE) append(SEPARATOR).append(state.label)
    }

    /** What [shortLabel] leaves out, for the tooltip beside it. */
    fun details(): String = listOfNotNull(
        serialNumber,
        apiLevel?.let { "API $it" },
        abi.takeIf { it.isNotEmpty() },
    ).joinToString(SEPARATOR)

    fun androidVersionLabel(): String = when {
        androidVersion.isNotBlank() && apiLevel != null -> "Android $androidVersion (API $apiLevel)"
        androidVersion.isNotBlank() -> "Android $androidVersion"
        apiLevel != null -> "API $apiLevel"
        else -> ""
    }

    /** With [withApiLevel] off, the marketing version alone — the API level is a detail. */
    private fun androidVersionLabel(withApiLevel: Boolean): String =
        if (withApiLevel || androidVersion.isBlank()) androidVersionLabel() else "Android $androidVersion"

    /** Unambiguous identification for confirmation prompts and notifications. */
    fun describe(): String =
        if (displayName == serialNumber) serialNumber else "$displayName ($serialNumber)"

    companion object {
        private const val EMULATOR_PREFIX = "Emulator: "
        private const val EMULATOR_SERIAL_PREFIX = "emulator-"
        private const val SEPARATOR = " \u00b7 "

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
