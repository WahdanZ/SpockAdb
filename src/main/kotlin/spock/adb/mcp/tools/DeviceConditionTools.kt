package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.command.BatteryLevelPreset
import spock.adb.command.ChargerSource
import spock.adb.command.DeviceConditionShell
import spock.adb.command.DeviceConditionTracker
import spock.adb.command.StandbyBucket
import spock.adb.command.deviceConditions
import spock.adb.command.forceDoze
import spock.adb.command.resetBattery
import spock.adb.command.resetDeviceConditions
import spock.adb.command.setBatteryLevel
import spock.adb.command.setCharger
import spock.adb.command.setStandbyBucket
import spock.adb.command.unplugBattery
import spock.adb.isAppInstall

/** `android_get_device_conditions` — Doze, the app's standby bucket, the battery override. */
class GetDeviceConditionsTool : AdbTool {
    override val name = "android_get_device_conditions"
    override val description =
        "Read the device conditions background work reacts to: whether the device is in deep Doze, " +
            "the app's App Standby bucket, whether the battery is overridden, and which of these Spock " +
            "has changed and not yet reset."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "App whose bucket to read. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val packageName = arguments.optionalString("packageName") ?: context.projectApplicationId()
        val conditions = target.device.deviceConditions(packageName)
        DeviceConditionTracker.reconcile(target.serialNumber, conditions)
        val changed = DeviceConditionTracker.conditions(target.serialNumber)

        return ToolResult.text(
            buildList {
                add("Deep Doze: ${conditions.deepIdle ?: "unknown"}${if (conditions.dozing) " (dozing)" else ""}")
                packageName?.let { add("Standby bucket of $it: ${conditions.bucket?.label ?: "unknown"}") }
                add(
                    "Battery: ${conditions.batteryLevel?.let { "$it%" } ?: "level unknown"}, " +
                        "${if (conditions.powered == true) "charging" else "not charging"}" +
                        if (conditions.batteryOverridden) " (overridden: dumpsys battery updates stopped)" else "",
                )
                add(
                    if (changed.isEmpty()) {
                        "Changed by Spock and not reset: nothing."
                    } else {
                        "Changed by Spock and not reset: ${changed.joinToString { it.describe() }}. " +
                            "Call android_reset_device_conditions when done."
                    },
                )
            }.joinToString("\n"),
        )
    }
}

/**
 * `android_force_doze` — destructive: it changes how every app on the device behaves until reset,
 * and a device left in forced Doze misbehaves for whoever uses it next.
 */
class ForceDozeTool : AdbTool {
    override val name = "android_force_doze"
    override val description =
        "Put the whole device into deep Doze now (battery reported unplugged, then dumpsys deviceidle " +
            "force-idle), to test how an app's jobs, alarms and network behave in Doze. Affects every app " +
            "until android_reset_device_conditions is called, so call it when done. Requires the " +
            "developer to confirm."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val approved = context.confirmDestructive(
            name,
            "Force the whole device into deep Doze until reset. Every app's background work is deferred.",
            target,
        )
        if (!approved) return ToolResult.error("The developer declined to force Doze.")
        return runCatching { target.device.forceDoze() }.fold(
            onSuccess = { ToolResult.text("$it Call android_reset_device_conditions when done.") },
            onFailure = { ToolResult.error(it.message ?: "Could not force Doze.") },
        )
    }
}

/** `android_set_standby_bucket` — move one app between App Standby buckets, read back. */
class SetStandbyBucketTool : AdbTool {
    override val name = "android_set_standby_bucket"
    override val description =
        "Put an app in an App Standby bucket (active, working_set, frequent, rare, restricted) to test how " +
            "its jobs and alarms are rationed. The bucket is read back: Android may keep the app higher, " +
            "e.g. apps allowed exact alarms never drop below working_set on Android 12+. Undo with " +
            "android_reset_device_conditions."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        enumeration(
            "bucket",
            "The bucket to put the app in.",
            StandbyBucket.SETTABLE.map { it.argument },
            required = true,
        )
        string("packageName", "App to move. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bucketName = arguments.requiredString("bucket")
        val bucket = StandbyBucket.fromArgument(bucketName)?.takeIf { it in StandbyBucket.SETTABLE }
            ?: return ToolResult.error(
                "Unknown bucket '$bucketName'. Use one of: ${StandbyBucket.SETTABLE.joinToString { it.argument }}.",
            )
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }
        return runCatching { device.setStandbyBucket(packageName, bucket) }.fold(
            onSuccess = { ToolResult.text(it) },
            onFailure = { ToolResult.error(it.message ?: "Could not set the standby bucket.") },
        )
    }
}

/** `android_unplug_battery` — report the battery as unplugged, so charging constraints are unmet. */
class UnplugBatteryTool : AdbTool {
    override val name = "android_unplug_battery"
    override val description =
        "Report the battery as unplugged (dumpsys battery unplug), so jobs that require charging wait and " +
            "Doze becomes possible. The real battery state is ignored until android_reset_device_conditions."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return runCatching { device.unplugBattery() }.fold(
            onSuccess = { ToolResult.text("$it Call android_reset_device_conditions when done.") },
            onFailure = { ToolResult.error(it.message ?: "Could not unplug the battery.") },
        )
    }
}

/** `android_set_battery_level` — report a battery percentage, discharging, and read it back. */
class SetBatteryLevelTool : AdbTool {
    override val name = "android_set_battery_level"
    override val description =
        "Report the battery at a given percentage and discharging (dumpsys battery unplug, then set " +
            "level), to test what an app does at a low battery: Battery Saver, deferred jobs, and " +
            "charging constraints. The level is read back. Common levels are " +
            BatteryLevelPreset.entries.joinToString { "${it.level} (${it.note})" } +
            ". The real battery is ignored until android_reset_battery or " +
            "android_reset_device_conditions."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        integer(
            "level",
            "Battery percentage, ${DeviceConditionShell.MIN_LEVEL}-${DeviceConditionShell.MAX_LEVEL}.",
            required = true,
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val level = arguments.requiredInt("level")
        if (level !in DeviceConditionShell.MIN_LEVEL..DeviceConditionShell.MAX_LEVEL) {
            return ToolResult.error(
                "A battery level is ${DeviceConditionShell.MIN_LEVEL}-${DeviceConditionShell.MAX_LEVEL}, " +
                    "not $level.",
            )
        }
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return runCatching { device.setBatteryLevel(level) }.fold(
            onSuccess = { ToolResult.text("$it Call android_reset_battery when done.") },
            onFailure = { ToolResult.error(it.message ?: "Could not set the battery level.") },
        )
    }
}

/** `android_set_charger` — switch one charger on its own, leaving the others and the level alone. */
class SetChargerTool : AdbTool {
    override val name = "android_set_charger"
    override val description =
        "Report one charger (ac, usb, wireless) as connected or disconnected, leaving the others and " +
            "the battery level alone. Use it for the case a plain unplug cannot express: AC off with USB " +
            "still on is a device discharging while still plugged into the machine. The state is read " +
            "back. Undo with android_reset_battery."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        enumeration("source", "The charger to switch.", ChargerSource.entries.map { it.argument }, required = true)
        boolean("connected", "True to report it connected, false to disconnect it.", required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.requiredString("source")
        val source = ChargerSource.fromDumpsysName(name)
            ?: return ToolResult.error(
                "Unknown charger '$name'. Use one of: ${ChargerSource.entries.joinToString { it.argument }}.",
            )
        val connected = arguments.requiredBoolean("connected")
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return runCatching { device.setCharger(source, connected) }.fold(
            onSuccess = { ToolResult.text("$it Call android_reset_battery when done.") },
            onFailure = { ToolResult.error(it.message ?: "Could not set the charger.") },
        )
    }
}

/** `android_reset_battery` — hand the battery back, leaving Doze and buckets alone. */
class ResetBatteryTool : AdbTool {
    override val name = "android_reset_battery"
    override val description =
        "Hand the battery back to the real hardware (dumpsys battery reset), undoing a level or unplug " +
            "override. Leaves forced Doze and standby buckets alone; use " +
            "android_reset_device_conditions to undo everything. Safe to call when nothing was changed."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return runCatching { device.resetBattery() }.fold(
            onSuccess = { ToolResult.text(it) },
            onFailure = { ToolResult.error(it.message ?: "Could not reset the battery.") },
        )
    }
}

/** `android_reset_device_conditions` — leave the device as it was found. */
class ResetDeviceConditionsTool : AdbTool {
    override val name = "android_reset_device_conditions"
    override val description =
        "Undo forced Doze, the battery override, and standby buckets Spock changed, and set the given " +
            "app's bucket back to active. Safe to call when nothing was changed."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "An app whose bucket to set back to active as well. Optional.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return ToolResult.text(device.resetDeviceConditions(arguments.optionalString("packageName")))
    }
}
