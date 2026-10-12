package spock.adb.mcp.tools

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import spock.adb.ShellQuote
import spock.adb.device.ops.InspectionOperations
import spock.adb.flutter.vmservice.Redaction
import spock.adb.models.FragmentData

/** `android_get_current_activity` — the resumed activity. */
class GetCurrentActivityTool : AdbTool {
    override val name = "android_get_current_activity"
    override val title = "Current activity"
    override val description =
        "The activity on screen, fully qualified: confirms which screen opened after a launch or link."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))

        val activity = InspectionOperations(device).currentActivity()
            ?: return ToolResult.error(
                "No resumed activity was reported. The screen may be locked or showing the launcher.",
            )
        return ToolResult.text(activity)
    }
}

/** `android_get_activity_stack` — the back stack across apps. */
class GetActivityStackTool : AdbTool {
    override val name = "android_get_activity_stack"
    override val title = "Activity stack"
    override val description =
        "The activity back stack by package, most recent first: how the user reached this screen."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj { deviceSerial() }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))

        val stack = InspectionOperations(device).activityStack()
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
    override val title = "Current fragments"
    override val description =
        "Visible fragments of the foreground app, nested ones included, when the activity is not enough."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        packageName()
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))

        return context.withPackage(arguments) { packageName ->
            val fragments = InspectionOperations(device).fragments(packageName)
            if (fragments.isEmpty()) return@withPackage ToolResult.text("No visible fragments were reported.")

            ToolResult.text(
                buildString {
                    fun render(list: List<FragmentData>, depth: Int) {
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
    val LINES = 1..MAX_LINES

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
            // Redacted here, at the way out: a Flutter debug build logs its VM Service address,
            // and the token in it lets whoever reads it run code in the app.
            output = Redaction.scrub(McpShell.run(device, command)),
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

    /** Which app's lines to read: [resolved], or the whole device when it is null. */
    data class Scope(val resolved: ResolvedPackage?, val wholeDeviceAsked: Boolean = false) {
        val packageName: String? get() = resolved?.name

        /**
         * What goes first in the answer: the app, when the agent did not name it, or that the read
         * covers the whole device though nobody asked for that.
         */
        val note: String? get() = if (resolved == null) WHOLE_DEVICE.takeUnless { wholeDeviceAsked } else resolved.note
    }

    /**
     * The app from [packageOrNull]; an explicit empty string means the whole device. So does an app
     * that cannot be resolved, which used to happen silently: an agent read every process's
     * lines as its app's.
     */
    fun JsonObject.logcatScope(context: ToolContext): Scope {
        if (has("packageName") && optionalString("packageName") == null) return Scope(null, wholeDeviceAsked = true)
        return Scope(context.packageOrNull(this))
    }

    const val WHOLE_DEVICE =
        "Whole-device log: no packageName was given, no app is selected in Spock, and no application ID " +
            "was resolved from the open project, so these lines come from every process. Pass packageName " +
            "for one app's lines."

    fun JsonObject.logcatLevel(): String = optionalString("minLevel")?.uppercase() ?: "V"

    fun JsonObject.logcatMaxLines(default: Int = DEFAULT_MAX_LINES): Int = optionalInt("maxLines", default, LINES)
}

/** `android_get_logcat` — recent log, filtered. */
class GetLogcatTool : AdbTool {
    override val name = "android_get_logcat"
    override val title = "Logcat"
    override val description =
        "Recent logcat lines, oldest first. Narrow it: one app's package, or minLevel E for crashes."
    override val safety = ToolSafety.READ_ONLY
    override val inputSchema: JsonObject = Schema.obj {
        string("packageName", "Only lines from this package's processes. $PACKAGE_DEFAULT_OR_DEVICE")
        enumeration("minLevel", "Minimum log level.", listOf("V", "D", "I", "W", "E", "F"))
        integer("maxLines", "Most recent lines. Default ${LogcatReader.DEFAULT_MAX_LINES}.", LogcatReader.LINES)
        deviceSerial()
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = context.requireIDevice(arguments.optionalString("deviceSerial"))
        val scope = with(LogcatReader) { arguments.logcatScope(context) }
        val read = with(LogcatReader) {
            LogcatReader.read(
                device = device,
                packageName = scope.packageName,
                minLevel = arguments.logcatLevel(),
                maxLines = arguments.logcatMaxLines(),
            )
        }
        return ToolResult.text(read.textOrExplanation()).noting(scope.note)
    }
}

/** `android_get_processes`, `android_get_battery_info`, `android_get_network_info`. */
class GetProcessesTool : AdbTool {
    override val name = "android_get_processes"
    override val title = "Processes"
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
    override val title = "Battery info"
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
    override val title = "Network info"
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
                append(withoutContinuations(McpShell.run(device, "ip -o addr show", maxChars = 4_000)))
            },
        )
    }

    internal companion object {
        /**
         * `ip -o` prints each address on one line, but keeps a `\` and padding where the
         * multi-line form would break (`scope host lo\       valid_lft forever`). An agent reads
         * that as a literal backslash in the interface name, so it becomes one space.
         */
        fun withoutContinuations(output: String): String = output.replace(CONTINUATION, " ")

        private val CONTINUATION = Regex(""" ?\\[ \t]+""")
    }
}
