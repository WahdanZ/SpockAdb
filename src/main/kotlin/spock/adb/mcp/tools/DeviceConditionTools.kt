package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.command.DeviceConditionTracker
import spock.adb.command.StandbyBucket
import spock.adb.command.deviceConditions
import spock.adb.command.forceDoze
import spock.adb.command.resetDeviceConditions
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
