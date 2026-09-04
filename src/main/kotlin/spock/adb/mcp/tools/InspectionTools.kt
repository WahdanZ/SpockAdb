package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.command.GetActivityCommand
import spock.adb.command.GetBackStackCommand
import spock.adb.command.GetFragmentsCommand

/** `android_get_current_activity` — the resumed activity. */
class GetCurrentActivityTool : AdbTool {
    override val name = "android_get_current_activity"
    override val description =
        "The fully qualified class name of the activity currently on screen. Use this to " +
            "confirm which screen an app is showing after launching it or opening a deep link."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val project = context.project
            ?: return ToolResult.error("No project is open, which this tool needs to run.")

        val activity = GetActivityCommand().execute(Any(), project, device.device)
            ?: return ToolResult.error(
                "No resumed activity was reported. The screen may be locked or showing the launcher.",
            )
        return ToolResult.text(activity)
    }
}

/** `android_get_activity_stack` — the back stack across apps. */
class GetActivityStackTool : AdbTool {
    override val name = "android_get_activity_stack"
    override val description =
        "The activity back stack, grouped by package, most recent first. Use this to " +
            "understand how the user reached the current screen."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val project = context.project
            ?: return ToolResult.error("No project is open, which this tool needs to run.")

        val stack = GetBackStackCommand().execute(Any(), project, device.device)
        if (stack.isEmpty()) return ToolResult.text("The activity stack is empty.")

        return ToolResult.text(
            stack.joinToString("\n\n") { entry ->
                buildString {
                    appendLine(entry.appPackage)
                    entry.activitiesList.forEachIndexed { index, activity ->
                        appendLine("  $index. $activity")
                    }
                }.trimEnd()
            },
        )
    }
}

/** `android_get_current_fragments` — visible fragments. */
class GetCurrentFragmentsTool : AdbTool {
    override val name = "android_get_current_fragments"
    override val description =
        "Fragments currently visible in the foreground app, including nested ones. Useful " +
            "when the activity alone does not identify the screen."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Package. Defaults to the open project's application ID.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireDevice(arguments.optionalString("deviceSerial"))
        val project = context.project
            ?: return ToolResult.error("No project is open, which this tool needs to run.")

        val fragments = GetFragmentsCommand().execute(
            context.resolvePackage(arguments),
            project,
            device.device,
        )
        if (fragments.isEmpty()) return ToolResult.text("No visible fragments were reported.")

        return ToolResult.text(
            buildString {
                fun render(list: List<spock.adb.models.FragmentData>, depth: Int) {
                    list.forEach { fragment ->
                        appendLine("  ".repeat(depth) + fragment.fragment)
                        render(fragment.innerFragments, depth + 1)
                    }
                }
                render(fragments, 0)
            }.trimEnd(),
        )
    }
}

/** `android_get_logcat` — recent log, filtered. */
class GetLogcatTool : AdbTool {
    override val name = "android_get_logcat"
    override val description =
        "Read recent logcat output. Always narrow it: filter by package to see one app, or " +
            "set minLevel to E to find crashes. Returns the most recent lines, oldest first."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string(
            "packageName",
            "Only lines from this package's processes. Defaults to the open project's " +
                "application ID. Pass an empty string to read the whole log.",
        )
        enumeration("minLevel", "Minimum log level.", listOf("V", "D", "I", "W", "E", "F"))
        integer("maxLines", "Maximum lines to return. Defaults to 300.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val maxLines = arguments.optionalInt("maxLines", DEFAULT_MAX_LINES).coerceIn(1, MAX_LINES)
        val level = arguments.optionalString("minLevel")?.uppercase() ?: "V"

        // Filter by PID rather than by text: matching the package name against message
        // content both misses lines and returns unrelated ones.
        val pids = arguments.resolveLogcatPackage(context)
            ?.let { pkg -> McpShell.run(device, "pidof ${ShellQuote.quote(pkg)}").trim() }
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val command = buildString {
            append("logcat -d -v threadtime -t ").append(maxLines)
            pids.forEach { append(" --pid=").append(it) }
            append(" *:").append(level)
        }

        val output = McpShell.run(device, command)
        return when {
            output.isBlank() && pids.isEmpty() -> ToolResult.text("Logcat returned nothing.")
            output.isBlank() -> ToolResult.text(
                "No matching log lines. The app may not be running — no process was found for it.",
            )
            else -> ToolResult.text(output)
        }
    }

    private fun JsonObject.resolveLogcatPackage(context: ToolContext): String? {
        // An explicit empty string is a deliberate "whole log" request.
        if (has("packageName") && optionalString("packageName") == null) return null
        return optionalString("packageName") ?: context.projectApplicationId()
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 300
        const val MAX_LINES = 5_000
    }
}

/** `android_get_processes`, `android_get_battery_info`, `android_get_network_info`. */
class GetProcessesTool : AdbTool {
    override val name = "android_get_processes"
    override val description =
        "Running processes with their PIDs. Filter by name to find one app's processes."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("filter", "Substring to match against the process name.")
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val filter = arguments.optionalString("filter")
        val output = McpShell.run(device, "ps -A")

        val lines = output.lines()
        val filtered = when (filter) {
            null -> lines
            else -> listOfNotNull(lines.firstOrNull()) +
                lines.drop(1).filter { it.contains(filter, ignoreCase = true) }
        }
        return ToolResult.text(filtered.joinToString("\n").ifBlank { "No matching processes." })
    }
}

class GetBatteryInfoTool : AdbTool {
    override val name = "android_get_battery_info"
    override val description =
        "Battery level, charging state, health and temperature."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return ToolResult.text(McpShell.run(device, "dumpsys battery"))
    }
}

class GetNetworkInfoTool : AdbTool {
    override val name = "android_get_network_info"
    override val description =
        "Network state: Wi-Fi and mobile data toggles, airplane mode, IP addresses and " +
            "active network interfaces."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        return ToolResult.text(
            buildString {
                appendLine("wifi_on:        ${McpShell.run(device, "settings get global wifi_on")}")
                appendLine("mobile_data:    ${McpShell.run(device, "settings get global mobile_data")}")
                appendLine("airplane_mode:  ${McpShell.run(device, "settings get global airplane_mode_on")}")
                appendLine()
                appendLine("interfaces:")
                append(McpShell.run(device, "ip -o addr show", maxChars = 4_000))
            },
        )
    }
}
