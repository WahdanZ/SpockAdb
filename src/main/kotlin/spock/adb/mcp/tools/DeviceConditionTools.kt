package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.command.BatteryLevelPreset
import spock.adb.command.ChargerSource
import spock.adb.command.DeviceConditionShell
import spock.adb.command.DeviceConditionTracker
import spock.adb.command.DeviceConditions
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
    override val title = "Device conditions"
    override val description =
        "Doze, the app's standby bucket and battery overrides, and which of them Spock has not reset."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string(
            "packageName",
            "$PACKAGE_DEFAULT \"\" for the device alone.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val (resolved, note) = scope(arguments, context)
        val packageName = resolved?.name
        val conditions = target.device.deviceConditions(packageName)
        DeviceConditionTracker.reconcile(target.serialNumber, conditions)
        val changed = DeviceConditionTracker.conditions(target.serialNumber)

        return ToolResult.text(
            buildList {
                add(deepDozeLine(conditions))
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
        ).noting(note)
    }

    /**
     * The app whose bucket to read, and the line that says where it came from. An explicit `""`
     * asks for the device alone, as it does for logcat: no app, so no bucket and no note.
     */
    private fun scope(arguments: JsonObject, context: ToolContext): Pair<ResolvedPackage?, String?> {
        if (arguments.has("packageName") && arguments.optionalString("packageName") == null) return null to null
        val resolved = context.packageOrNull(arguments) ?: return null to NO_BUCKET
        return resolved to resolved.note
    }

    private companion object {
        const val NO_BUCKET =
            "Standby bucket: not read. No packageName was given, no app is selected in Spock, and no " +
                "application ID was resolved from the open project."
    }
}

/**
 * `Deep Doze: no (state ACTIVE)`: the answer first, the device's own word after it.
 *
 * The state used to be the answer, and `ACTIVE` reads as "Doze is active" when it is the device's
 * normal, awake state — the opposite. Only `IDLE` is dozing, as [DeviceConditions.dozing] has
 * it: the states on the way there (`IDLE_PENDING`, `SENSING`, `LOCATING`) are not, and neither is
 * `IDLE_MAINTENANCE`, the window in which Doze lets deferred work run.
 */
internal fun deepDozeLine(conditions: DeviceConditions): String = when (val state = conditions.deepIdle) {
    null -> "Deep Doze: unknown"
    else -> "Deep Doze: ${if (conditions.dozing) "yes" else "no"} (state $state)"
}

/**
 * `android_force_doze` — destructive: it changes how every app on the device behaves until reset,
 * and a device left in forced Doze misbehaves for whoever uses it next.
 */
class ForceDozeTool : AdbTool {
    override val name = "android_force_doze"
    override val title = "Force Doze"
    override val description =
        "Force the whole device into deep Doze. Affects every app until android_reset_device_conditions; " +
            "the developer confirms."
    override val safety = ToolSafety.DESTRUCTIVE
    override val idempotent = true
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
    override val title = "Set standby bucket"
    override val description =
        "Put an app in an App Standby bucket, read back as Android may keep it higher. Undo with " +
            "android_reset_device_conditions."
    override val safety = ToolSafety.SAFE_ACTION
    override val deviceWide = true
    override val idempotent = true
    override val inputSchema: JsonObject = Schema.obj {
        enumeration(
            "bucket",
            "The bucket to put the app in.",
            StandbyBucket.SETTABLE.map { it.argument },
            required = true,
        )
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val bucketName = arguments.requiredString("bucket")
        val bucket = StandbyBucket.fromArgument(bucketName)?.takeIf { it in StandbyBucket.SETTABLE }
            ?: return ToolResult.error(
                "Unknown bucket '$bucketName'. Use one of: ${StandbyBucket.SETTABLE.joinToString { it.argument }}.",
            )
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return context.withPackage(arguments) { packageName ->
            if (!device.isAppInstall(packageName)) {
                return@withPackage ToolResult.error("Package '$packageName' is not installed on this device.")
            }
            runCatching { device.setStandbyBucket(packageName, bucket) }.fold(
                onSuccess = { ToolResult.text(it) },
                onFailure = { ToolResult.error(it.message ?: "Could not set the standby bucket.") },
            )
        }
    }
}

/** `android_unplug_battery` — report the battery as unplugged, so charging constraints are unmet. */
class UnplugBatteryTool : AdbTool {
    override val name = "android_unplug_battery"
    override val title = "Unplug battery"
    override val description =
        "Report the battery as unplugged, so charging-only jobs wait and Doze becomes possible. Undo " +
            "with android_reset_battery."
    override val safety = ToolSafety.SAFE_ACTION
    override val deviceWide = true
    override val idempotent = true
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
    override val title = "Set battery level"
    override val description =
        "Report the battery at a percentage, discharging. Common levels: " +
            BatteryLevelPreset.entries.joinToString { "${it.level} (${it.note})" } +
            ". Undo with android_reset_battery."
    override val safety = ToolSafety.SAFE_ACTION
    override val deviceWide = true
    override val idempotent = true
    override val inputSchema: JsonObject = Schema.obj {
        integer("level", "Battery percentage.", LEVELS, required = true)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val level = arguments.requiredInt("level", LEVELS)
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return runCatching { device.setBatteryLevel(level) }.fold(
            onSuccess = { ToolResult.text("$it Call android_reset_battery when done.") },
            onFailure = { ToolResult.error(it.message ?: "Could not set the battery level.") },
        )
    }

    private companion object {
        val LEVELS = DeviceConditionShell.MIN_LEVEL..DeviceConditionShell.MAX_LEVEL
    }
}

/** `android_set_charger` — switch one charger on its own, leaving the others and the level alone. */
class SetChargerTool : AdbTool {
    override val name = "android_set_charger"
    override val title = "Set charger"
    override val description =
        "Report one charger as connected or not, leaving the others alone. Undo with android_reset_battery."
    override val safety = ToolSafety.SAFE_ACTION
    override val deviceWide = true
    override val idempotent = true
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
    override val title = "Reset battery"
    override val description =
        "Undo battery level, unplug and charger overrides. android_reset_device_conditions also undoes Doze."
    override val safety = ToolSafety.SAFE_ACTION
    override val idempotent = true
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
    override val title = "Reset device conditions"
    override val description =
        "Undo forced Doze, battery overrides and the standby buckets Spock changed. Safe to call anytime."
    override val safety = ToolSafety.SAFE_ACTION
    override val idempotent = true
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Also set this app's bucket back to active.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return ToolResult.text(device.resetDeviceConditions(arguments.optionalString("packageName")))
    }
}
