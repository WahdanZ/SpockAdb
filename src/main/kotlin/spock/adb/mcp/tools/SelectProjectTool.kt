package spock.adb.mcp.tools

import com.google.gson.JsonObject

/**
 * `android_select_project` — says which open project later calls are about.
 *
 * An MCP client connects to the IDE, not to a project. With one project open there is
 * nothing to choose and this tool is never needed. With several, the application ID, the
 * sources an Activity resolves against and the default logcat filter all differ, so the
 * plugin refuses to guess and asks which — exactly as `android_select_device` does when
 * several devices are attached.
 */
class SelectProjectTool : AdbTool {

    override val name = "android_select_project"

    override val description =
        "Select which open project later calls are about, for an IDE with more than one " +
            "project open. Only needed when a tool reports that the project is ambiguous — " +
            "that error lists the names to choose from. With a single project open, every " +
            "tool already targets it and this call is unnecessary."

    override val safety = ToolSafety.SAFE_ACTION

    override val inputSchema: JsonObject = Schema.obj {
        string(
            "projectName",
            "Name of the open project, as listed in the ambiguity error.",
            required = true,
        )
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val selected = context.selectProject(arguments.requiredString("projectName"))

        // Report the application ID too: it is the thing the selection actually changes, so
        // the agent can see immediately whether it picked the project it meant.
        val applicationId = context.projectApplicationId()
        return ToolResult.text(
            "Selected project '$selected'. Later calls target it.\n" +
                "application id: " +
                (applicationId ?: "could not be resolved yet — let Gradle sync finish."),
        )
    }
}
