package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.clearAppData
import spock.adb.forceKillApp
import spock.adb.getDefaultActivityForApplication
import spock.adb.isAppInstall
import spock.adb.startActivity

/** `android_list_packages` — installed packages, optionally filtered. */
class ListPackagesTool : AdbTool {
    override val name = "android_list_packages"
    override val description =
        "List installed package names. Use the filter to narrow the list — a device has " +
            "hundreds of packages. Set includeSystem to true to include system packages, " +
            "which are excluded by default."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("filter", "Substring to match against package names, e.g. 'com.example'.")
        boolean("includeSystem", "Include system packages. Defaults to false.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val includeSystem = arguments.optionalBoolean("includeSystem", false)
        val filter = arguments.optionalString("filter")

        val command = buildString {
            append("pm list packages")
            if (!includeSystem) append(" -3")
            filter?.let { append(" ").append(ShellQuote.quote(it)) }
        }

        val packages = McpShell.run(device, command)
            .lines()
            .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotBlank) }
            .sorted()

        return when {
            packages.isEmpty() && filter != null ->
                ToolResult.text("No installed package matches '$filter'.")
            packages.isEmpty() -> ToolResult.text("No packages found.")
            else -> ToolResult.text(packages.joinToString("\n"))
        }
    }
}

/** `android_get_package_info` — version, permissions, components. */
class GetPackageInfoTool : AdbTool {
    override val name = "android_get_package_info"
    override val description =
        "Version name and code, UID, install location, declared and granted permissions, " +
            "and components for one package. Use this to check whether a permission is " +
            "actually granted before concluding a permission problem."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to inspect. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)

        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }
        return ToolResult.text(
            McpShell.run(device, "dumpsys package ${ShellQuote.quote(packageName)}"),
        )
    }
}

/** `android_launch_app` — start the launcher activity. */
class LaunchAppTool : AdbTool {
    override val name = "android_launch_app"
    override val description =
        "Launch an app's default launcher activity. Use android_open_deep_link instead if " +
            "you need to open a specific screen."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to launch. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)

        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }
        val activity = device.getDefaultActivityForApplication(packageName)
        if (activity.isBlank()) {
            return ToolResult.error("'$packageName' declares no launchable activity.")
        }
        device.startActivity(activity)
        return ToolResult.text("Launched $activity.")
    }
}

/** `android_stop_app` — force-stop. */
class StopAppTool : AdbTool {
    override val name = "android_stop_app"
    override val description =
        "Force-stop an app. The app is not removed from recents and its data is untouched."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to stop. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        device.forceKillApp(packageName, McpShell.DEFAULT_TIMEOUT_SECONDS)
        return ToolResult.text("Force-stopped $packageName.")
    }
}

/** `android_restart_app` — stop then launch. */
class RestartAppTool : AdbTool {
    override val name = "android_restart_app"
    override val description =
        "Force-stop an app and launch it again. Useful for reproducing a cold start."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to restart. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)

        if (!device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }
        device.forceKillApp(packageName, McpShell.DEFAULT_TIMEOUT_SECONDS)
        val activity = device.getDefaultActivityForApplication(packageName)
        if (activity.isBlank()) {
            return ToolResult.error("'$packageName' declares no launchable activity.")
        }
        device.startActivity(activity)
        return ToolResult.text("Restarted $packageName ($activity).")
    }
}

/** `android_clear_app_data` — destructive, always confirmed. */
class ClearAppDataTool : AdbTool {
    override val name = "android_clear_app_data"
    override val description =
        "Delete all data for an app: shared preferences, databases and caches. This cannot " +
            "be undone and requires the developer to confirm before it runs."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to wipe. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)

        if (!target.device.isAppInstall(packageName)) {
            return ToolResult.error("Package '$packageName' is not installed on this device.")
        }
        val approved = context.confirmDestructive(
            name,
            "Delete all data for $packageName (shared preferences, databases and caches).",
            target,
        )
        if (!approved) {
            return ToolResult.error("The developer declined to clear data for $packageName.")
        }
        target.device.clearAppData(packageName, McpShell.DEFAULT_TIMEOUT_SECONDS)
        return ToolResult.text("Cleared all data for $packageName.")
    }
}

/** `android_grant_permission` / `android_revoke_permission`. */
class GrantPermissionTool : AdbTool {
    override val name = "android_grant_permission"
    override val description =
        "Grant one runtime permission to an app, e.g. android.permission.CAMERA."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("permission", "Full permission name, e.g. android.permission.CAMERA.", required = true)
        string("packageName", "Package. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        val permission = arguments.requiredString("permission")

        val output = McpShell.run(
            device,
            "pm grant ${ShellQuote.quote(packageName)} ${ShellQuote.quote(permission)}",
        )
        return if (output.isBlank()) {
            ToolResult.text("Granted $permission to $packageName.")
        } else {
            ToolResult.error("Could not grant $permission: $output")
        }
    }
}

class RevokePermissionTool : AdbTool {
    override val name = "android_revoke_permission"
    override val description =
        "Revoke one runtime permission from an app. Revoking a permission an app is using " +
            "usually kills its process, which is often the point when testing."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        string("permission", "Full permission name, e.g. android.permission.CAMERA.", required = true)
        string("packageName", "Package. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val packageName = context.resolvePackage(arguments)
        val permission = arguments.requiredString("permission")

        val approved = context.confirmDestructive(
            name,
            "Revoke $permission from $packageName.",
            target,
        )
        if (!approved) {
            return ToolResult.error("The developer declined to revoke $permission.")
        }
        val output = McpShell.run(
            target.device,
            "pm revoke ${ShellQuote.quote(packageName)} ${ShellQuote.quote(permission)}",
        )
        return if (output.isBlank()) {
            ToolResult.text("Revoked $permission from $packageName.")
        } else {
            ToolResult.error("Could not revoke $permission: $output")
        }
    }
}

/**
 * Package argument resolution shared by the app tools.
 *
 * Defaulting to the open project's application ID is what makes "restart the app" work
 * without the agent first having to discover which app the developer is working on.
 */
internal fun ToolContext.resolvePackage(arguments: JsonObject): String =
    arguments.optionalString("packageName")
        ?: projectApplicationId()
        ?: throw IllegalStateException(
            "No packageName was given and the application ID could not be resolved from the " +
                "open project. Pass packageName explicitly, or open an Android project and " +
                "let its Gradle sync finish.",
        )
