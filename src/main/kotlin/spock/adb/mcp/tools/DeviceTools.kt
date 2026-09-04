package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.device.ConnectedDevice

/** `android_list_devices` — every device ADB currently reports, usable or not. */
class ListDevicesTool : AdbTool {
    override val name = "android_list_devices"
    override val description =
        "List every Android device and emulator currently connected, with model, Android " +
            "version, API level, architecture and connection state. Call this first to find " +
            "a device serial. Devices that are offline or unauthorized are listed too, " +
            "with their state, so you can tell the user why they cannot be used."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.empty()

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val devices = context.devices()
        if (devices.isEmpty()) {
            return ToolResult.text(
                "No devices are connected. Attach a device over USB or start an emulator.",
            )
        }
        return ToolResult.text(devices.joinToString("\n") { it.describeForAgent() })
    }
}

/** `android_get_device_info` — full detail for one device. */
class GetDeviceInfoTool : AdbTool {
    override val name = "android_get_device_info"
    override val description =
        "Detailed information about one device: model, manufacturer, Android version, API " +
            "level, ABI, serial, whether it is an emulator, and its connection state."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val info = context.requireDevice(arguments.optionalString("deviceSerial")).info
        return ToolResult.text(
            buildString {
                appendLine("serial:        ${info.serialNumber}")
                appendLine("name:          ${info.displayName}")
                appendLine("manufacturer:  ${info.manufacturer.ifBlank { "unknown" }}")
                appendLine("model:         ${info.model.ifBlank { "unknown" }}")
                appendLine("android:       ${info.androidVersionLabel().ifBlank { "unknown" }}")
                appendLine("abi:           ${info.abi.ifBlank { "unknown" }}")
                appendLine("type:          ${if (info.isEmulator) "emulator" else "physical device"}")
                append("state:         ${info.state.label}")
            },
        )
    }
}

/** `android_select_device` — pins the device later calls target. */
class SelectDeviceTool : AdbTool {
    override val name = "android_select_device"
    override val description =
        "Select the device that later tool calls target by default, so you do not have to " +
            "pass deviceSerial every time. Use android_list_devices first to get a serial."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("deviceSerial", "Serial of the device to select.", required = true)
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val selected = context.selectDevice(arguments.requiredString("deviceSerial"))
        return ToolResult.text("Selected ${selected.info.describe()}. Later calls target this device.")
    }
}

internal fun ConnectedDevice.describeForAgent(): String = buildString {
    append(info.serialNumber)
    append("  ").append(info.displayName)
    info.androidVersionLabel().takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
    info.abi.takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
    append("  ").append(if (info.isEmulator) "emulator" else "physical")
    append("  ").append(info.state.label)
}
