package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
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

/**
 * Reading logcat, shared by `android_get_logcat` and `android_get_debug_context`.
 *
 * Filtering by PID rather than by text lives here so both callers inherit it: matching a
 * package name against message content both misses lines the app wrote under another tag and
 * returns unrelated lines that merely mention it.
 */
internal object LogcatReader {

    const val DEFAULT_MAX_LINES = 300
    const val MAX_LINES = 5_000

    /** @param packageName null reads the whole log; otherwise only that package's processes. */
    fun read(device: IDevice, packageName: String?, minLevel: String, maxLines: Int): Read {
        val pids = packageName
            ?.let { McpShell.run(device, "pidof ${ShellQuote.quote(it)}").trim() }
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val command = buildString {
            append("logcat -d -v threadtime -t ").append(maxLines)
            pids.forEach { append(" --pid=").append(it) }
            append(" *:").append(minLevel)
        }
        return Read(
            output = McpShell.run(device, command),
            filteredByPackage = packageName != null,
            pidCount = pids.size,
        )
    }

    data class Read(val output: String, val filteredByPackage: Boolean, val pidCount: Int) {

        /** Why nothing came back, which is not always an error worth alarming the agent with. */
        val emptyExplanation: String
            get() = when {
                filteredByPackage && pidCount == 0 ->
                    "No matching log lines. The app may not be running — no process was found for it."
                else -> "Logcat returned nothing."
            }

        fun textOrExplanation(): String = output.ifBlank { emptyExplanation }
    }

    /** Absent `packageName` means the open project's app; an explicit empty string means all. */
    fun JsonObject.logcatPackage(context: ToolContext): String? {
        if (has("packageName") && optionalString("packageName") == null) return null
        return optionalString("packageName") ?: context.projectApplicationId()
    }

    fun JsonObject.logcatLevel(): String = optionalString("minLevel")?.uppercase() ?: "V"

    fun JsonObject.logcatMaxLines(default: Int = DEFAULT_MAX_LINES): Int =
        optionalInt("maxLines", default).coerceIn(1, MAX_LINES)
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
        val read = with(LogcatReader) {
            LogcatReader.read(
                device = device,
                packageName = arguments.logcatPackage(context),
                minLevel = arguments.logcatLevel(),
                maxLines = arguments.logcatMaxLines(),
            )
        }
        return ToolResult.text(read.textOrExplanation())
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
