package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.device.ops.AppNotInstalledException
import spock.adb.device.ops.AppOperations

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

        if (!AppOperations(device).isInstalled(packageName)) {
            return ToolResult.error(AppNotInstalledException.message(packageName))
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
        val operations = context.appOperations(arguments)
        val packageName = context.resolvePackage(arguments)

        return appOperation { "Launched ${operations.launch(packageName)}." }
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
        val operations = context.appOperations(arguments)
        val packageName = context.resolvePackage(arguments)

        return appOperation {
            operations.stop(packageName)
            "Force-stopped $packageName."
        }
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
        val operations = context.appOperations(arguments)
        val packageName = context.resolvePackage(arguments)

        return appOperation { "Restarted $packageName (${operations.restart(packageName)})." }
    }
}

/**
 * `android_simulate_process_death` — the tool window's Process Death, for agents.
 *
 * A safe action: it affects only the app under test, it is what a developer does by hand from
 * the tool window, and the relaunch restores the app. Before it existed, agents reached for
 * `am kill` through `android_run_adb_command`, which asks the developer every time.
 */
class SimulateProcessDeathTool : AdbTool {
    override val name = "android_simulate_process_death"
    override val description =
        "Simulate Android killing the app in the background to reclaim memory: send it to the " +
            "background, kill its process, and relaunch it the way the launcher icon does, so Android " +
            "recreates the top screen from saved instance state. Use it to test that a screen survives " +
            "process death. Not the same as android_stop_app or android_restart_app, which force-stop " +
            "and discard saved state. The app must be running. Reports the pid before and after, and " +
            "fails if the process did not die."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to kill. Defaults to the open project's application ID.")
        boolean(
            "relaunch",
            "Bring the app back after the kill. Defaults to true. Pass false to inspect the device " +
                "while the process is dead, then restore it from recents (android_press_key APP_SWITCH).",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)
        val packageName = context.resolvePackage(arguments)
        val relaunch = arguments.optionalBoolean("relaunch", default = true)

        return appOperation {
            val death = operations.simulateProcessDeath(packageName, relaunch)
            val killed = "Killed $packageName in the background (pid ${death.pidsBefore.joinToString()} is gone)."
            when {
                death.relaunched == null ->
                    "$killed Not relaunched: its task is still in recents, and restoring it from there " +
                        "recreates the top screen from saved state."
                death.pidsAfter.isEmpty() ->
                    "$killed Relaunched ${death.relaunched}, but no new process had appeared yet; " +
                        "check with android_get_processes."
                else ->
                    "$killed Relaunched ${death.relaunched} as pid ${death.pidsAfter.joinToString()}. " +
                        "The top screen was recreated from saved instance state: whatever differs from " +
                        "before the kill is state it does not save."
            }
        }
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
        val operations = AppOperations(target.device)
        val packageName = context.resolvePackage(arguments)

        // Asked before the confirmation, not after: offering to wipe an app that is not on
        // the device is a question with no right answer.
        if (!operations.isInstalled(packageName)) {
            return ToolResult.error(AppNotInstalledException.message(packageName))
        }
        val approved = context.confirmDestructive(
            name,
            "Delete all data for $packageName (shared preferences, databases and caches).",
            target,
        )
        if (!approved) {
            return ToolResult.error("The developer declined to clear data for $packageName.")
        }
        return appOperation {
            operations.clearData(packageName)
            "Cleared all data for $packageName."
        }
    }
}

/** `android_clear_app_cache` — cache only, so nothing a developer has to re-seed is lost. */
class ClearAppCacheTool : AdbTool {
    override val name = "android_clear_app_cache"
    override val description =
        "Delete only an app's internal cache and code_cache. Shared preferences, databases " +
            "and files are left alone. Requires a debuggable build; use " +
            "android_clear_app_data to wipe everything."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package whose cache to clear. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)
        val packageName = context.resolvePackage(arguments)

        return appOperation { operations.clearCache(packageName) }
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
internal fun ToolContext.appOperations(arguments: JsonObject): AppOperations =
    AppOperations(requireIDevice(arguments.optionalString("deviceSerial")))

/**
 * Runs one app operation and reports what it did, or why it would not.
 *
 * [AppOperations] throws, which is the right shape for the tool window — an action that
 * failed is reported the same way whatever failed. An agent reads a result instead, so the
 * refusal has to arrive as one it can explain and act on.
 */
private inline fun appOperation(block: () -> String): ToolResult =
    runCatching(block).fold(
        onSuccess = ToolResult::text,
        onFailure = { ToolResult.error(it.message ?: "${it.javaClass.simpleName} while running the operation.") },
    )

internal fun ToolContext.resolvePackage(arguments: JsonObject): String =
    arguments.optionalString("packageName")
        ?: projectApplicationId()
        ?: throw IllegalStateException(
            "No packageName was given and the application ID could not be resolved from the " +
                "open project. Pass packageName explicitly, or open an Android project and " +
                "let its Gradle sync finish.",
        )

/** `android_uninstall_app` — removes the app entirely. Destructive, always confirmed. */
class UninstallAppTool : AdbTool {
    override val name = "android_uninstall_app"
    override val description =
        "Uninstall an app from the device, removing the application and all of its data. " +
            "This cannot be undone and requires the developer to confirm before it runs."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package to uninstall. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val operations = AppOperations(target.device)
        val packageName = context.resolvePackage(arguments)

        if (!operations.isInstalled(packageName)) {
            return ToolResult.error(AppNotInstalledException.message(packageName))
        }
        val approved = context.confirmDestructive(
            name,
            "Uninstall $packageName, removing the application and all of its data.",
            target,
        )
        if (!approved) {
            return ToolResult.error("The developer declined to uninstall $packageName.")
        }
        return appOperation {
            operations.uninstall(packageName)
            "Uninstalled $packageName."
        }
    }
}
