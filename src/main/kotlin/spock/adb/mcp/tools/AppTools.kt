package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.command.PackageInfoSummary
import spock.adb.device.ops.AppNotInstalledException
import spock.adb.device.ops.AppOperations

/** `android_list_packages` — installed packages, optionally filtered. */
class ListPackagesTool : AdbTool {
    override val name = "android_list_packages"
    override val title = "List packages"
    override val description =
        "List installed packages, system ones excluded unless includeSystem. Narrow it with filter."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("filter", "Substring to match against package names, e.g. 'com.example'.")
        boolean("includeSystem", "Include system packages.")
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

/**
 * `android_get_package_info` — version, permissions, components.
 *
 * A summary parsed out of `dumpsys package` by default; see [PackageInfoSummary] for why.
 */
class GetPackageInfoTool : AdbTool {
    override val name = "android_get_package_info"
    override val title = "Package info"
    override val description =
        "An installed package's version, SDKs, launcher activity, and granted and denied permissions."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        boolean(
            "raw",
            "Full dumpsys output instead, cut at 40,000 chars.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val raw = arguments.optionalBoolean("raw", false)

        return context.withPackage(arguments) { packageName ->
            if (!AppOperations(device).isInstalled(packageName)) {
                return@withPackage ToolResult.error(AppNotInstalledException.message(packageName))
            }
            // The summary reads the whole dump: the package's own section comes after the resolver
            // tables, so a capped dump can end before the version is printed.
            val dumpsys = McpShell.run(
                device,
                "dumpsys package ${ShellQuote.quote(packageName)}",
                maxChars = if (raw) McpShell.DEFAULT_MAX_CHARS else Int.MAX_VALUE,
            )
            ToolResult.text(if (raw) dumpsys else PackageInfoSummary.parse(packageName, dumpsys).describe())
        }
    }
}

/** `android_launch_app` — start the launcher activity. */
class LaunchAppTool : AdbTool {
    override val name = "android_launch_app"
    override val title = "Launch app"
    override val description =
        "Launch an app's launcher activity; android_open_deep_link opens a specific screen."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)

        return context.withPackage(arguments) { packageName ->
            appOperation { "Launched ${operations.launch(packageName)}." }
        }
    }
}

/** `android_stop_app` — force-stop. */
class StopAppTool : AdbTool {
    override val name = "android_stop_app"
    override val title = "Stop app"
    override val description =
        "Force-stop an app. The app is not removed from recents and its data is untouched."
    override val safety = ToolSafety.SAFE_ACTION
    override val idempotent = true
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)

        return context.withPackage(arguments) { packageName ->
            appOperation {
                operations.stop(packageName)
                "Force-stopped $packageName."
            }
        }
    }
}

/** `android_restart_app` — stop then launch. */
class RestartAppTool : AdbTool {
    override val name = "android_restart_app"
    override val title = "Restart app"
    override val description =
        "Force-stop an app and launch it again. Useful for reproducing a cold start."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)

        return context.withPackage(arguments) { packageName ->
            appOperation { "Restarted $packageName (${operations.restart(packageName)})." }
        }
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
    override val title = "Simulate process death"
    override val description =
        "Kill a backgrounded app as Android would and relaunch it, to test restoring saved state."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        boolean(
            "relaunch",
            "Relaunch after the kill. Default true; false leaves it to restore from recents.",
        )
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)
        val relaunch = arguments.optionalBoolean("relaunch", default = true)

        return context.withPackage(arguments) { packageName ->
            appOperation {
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
}

/** `android_clear_app_data` — destructive, always confirmed. */
class ClearAppDataTool : AdbTool {
    override val name = "android_clear_app_data"
    override val title = "Clear app data"
    override val description =
        "Delete all of an app's data. Cannot be undone; the developer confirms."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val operations = AppOperations(target.device)

        return context.withResolvedPackage(arguments) { resolved ->
            val packageName = resolved.name
            // Asked before the confirmation, not after: offering to wipe an app that is not on
            // the device is a question with no right answer.
            if (!operations.isInstalled(packageName)) {
                return@withResolvedPackage ToolResult.error(AppNotInstalledException.message(packageName))
            }
            val approved = context.confirmDestructive(
                name,
                resolved.confirming("Delete all data for $packageName (shared preferences, databases and caches)."),
                target,
            )
            if (!approved) {
                return@withResolvedPackage ToolResult.error("The developer declined to clear data for $packageName.")
            }
            appOperation {
                operations.clearData(packageName)
                "Cleared all data for $packageName."
            }
        }
    }
}

/** `android_clear_app_cache` — cache only, so nothing a developer has to re-seed is lost. */
class ClearAppCacheTool : AdbTool {
    override val name = "android_clear_app_cache"
    override val title = "Clear app cache"
    override val description =
        "Delete only an app's cache and code_cache, keeping its data. Needs a debuggable build."
    override val safety = ToolSafety.SAFE_ACTION
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val operations = context.appOperations(arguments)

        return context.withPackage(arguments) { packageName -> appOperation { operations.clearCache(packageName) } }
    }
}

/** `android_grant_permission` / `android_revoke_permission`. */
class GrantPermissionTool : AdbTool {
    override val name = "android_grant_permission"
    override val title = "Grant permission"
    override val description =
        "Grant one runtime permission to an app, e.g. android.permission.CAMERA."
    override val safety = ToolSafety.SAFE_ACTION
    override val idempotent = true
    override val inputSchema: JsonObject = Schema.obj {
        string("permission", "Full permission name, e.g. android.permission.CAMERA.", required = true)
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val permission = arguments.requiredString("permission")

        return context.withPackage(arguments) { packageName ->
            val output = McpShell.run(
                device,
                "pm grant ${ShellQuote.quote(packageName)} ${ShellQuote.quote(permission)}",
            )
            if (output.isBlank()) {
                ToolResult.text("Granted $permission to $packageName.")
            } else {
                ToolResult.error("Could not grant $permission: $output")
            }
        }
    }
}

class RevokePermissionTool : AdbTool {
    override val name = "android_revoke_permission"
    override val title = "Revoke permission"
    override val description =
        "Revoke one runtime permission from an app, which usually kills its process."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        string("permission", "Full permission name, e.g. android.permission.CAMERA.", required = true)
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val permission = arguments.requiredString("permission")

        return context.withResolvedPackage(arguments) { resolved ->
            val packageName = resolved.name
            val approved = context.confirmDestructive(
                name,
                resolved.confirming("Revoke $permission from $packageName."),
                target,
            )
            if (!approved) {
                return@withResolvedPackage ToolResult.error("The developer declined to revoke $permission.")
            }
            val output = McpShell.run(
                target.device,
                "pm revoke ${ShellQuote.quote(packageName)} ${ShellQuote.quote(permission)}",
            )
            if (output.isBlank()) {
                ToolResult.text("Revoked $permission from $packageName.")
            } else {
                ToolResult.error("Could not revoke $permission: $output")
            }
        }
    }
}

/**
 * The app operations on the call's device. The app itself comes from [withPackage]: defaulting
 * to the app the developer is working on is what makes "restart the app" work without the agent
 * first having to discover which one that is.
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

/** `android_uninstall_app` — removes the app entirely. Destructive, always confirmed. */
class UninstallAppTool : AdbTool {
    override val name = "android_uninstall_app"
    override val title = "Uninstall app"
    override val description =
        "Uninstall an app and all its data. Cannot be undone; the developer confirms."
    override val safety = ToolSafety.DESTRUCTIVE
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val target = context.requireDevice(arguments.optionalString("deviceSerial"))
        val operations = AppOperations(target.device)

        return context.withResolvedPackage(arguments) { resolved ->
            val packageName = resolved.name
            if (!operations.isInstalled(packageName)) {
                return@withResolvedPackage ToolResult.error(AppNotInstalledException.message(packageName))
            }
            val approved = context.confirmDestructive(
                name,
                resolved.confirming("Uninstall $packageName, removing the application and all of its data."),
                target,
            )
            if (!approved) {
                return@withResolvedPackage ToolResult.error("The developer declined to uninstall $packageName.")
            }
            appOperation {
                operations.uninstall(packageName)
                "Uninstalled $packageName."
            }
        }
    }
}
