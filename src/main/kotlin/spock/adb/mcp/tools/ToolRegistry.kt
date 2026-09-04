package spock.adb.mcp.tools

/**
 * The set of tools exposed to MCP clients and, later, to the plugin's own AI layer.
 *
 * One registry for both so there is a single definition of what an agent may do to a device
 * and under what safety level — two parallel implementations would drift, and the one that
 * drifted would be the one enforcing the safety rules.
 */
object ToolRegistry {

    private val tools: List<AdbTool> = listOf(
        // Devices
        ListDevicesTool(),
        GetDeviceInfoTool(),
        SelectDeviceTool(),
        // Applications
        ListPackagesTool(),
        GetPackageInfoTool(),
        LaunchAppTool(),
        StopAppTool(),
        RestartAppTool(),
        ClearAppDataTool(),
        GrantPermissionTool(),
        RevokePermissionTool(),
        // Inspection
        GetCurrentActivityTool(),
        GetActivityStackTool(),
        GetCurrentFragmentsTool(),
        GetLogcatTool(),
        GetProcessesTool(),
        GetBatteryInfoTool(),
        GetNetworkInfoTool(),
        // Interaction
        TakeScreenshotTool(),
        GetUiHierarchyTool(),
        OpenDeepLinkTool(),
        InputTextTool(),
        TapTool(),
        SwipeTool(),
        PressKeyTool(),
        // Escape hatch
        RunAdbCommandTool(),
    )

    private val byName: Map<String, AdbTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) {
            "Duplicate MCP tool names: " +
                tools.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        }
    }

    fun all(): List<AdbTool> = tools

    fun find(name: String): AdbTool? = byName[name]

    fun bySafety(safety: ToolSafety): List<AdbTool> = tools.filter { it.safety == safety }
}
