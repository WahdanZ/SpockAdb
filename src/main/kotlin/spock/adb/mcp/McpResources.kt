package spock.adb.mcp

import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.describeForAgent

/**
 * Read-only views an MCP client can pull without making a tool call.
 *
 * Every resource is read fresh on request and stamped with the time it was read. A resource
 * an agent believes is current but is minutes old is worse than no resource at all — it
 * will reason confidently about a screen that has since changed.
 */
object McpResources {

    private val resources: List<McpResource> = listOf(
        McpResource(
            uri = "android://devices",
            name = "Connected devices",
            description = "Every device ADB currently reports, with state and metadata.",
        ) { context ->
            val devices = context.devices()
            if (devices.isEmpty()) {
                "No devices connected."
            } else {
                devices.joinToString("\n") { it.describeForAgent() }
            }
        },
        McpResource(
            uri = "android://device/selected",
            name = "Selected device",
            description = "The device that tool calls target by default.",
        ) { context ->
            runCatching { context.requireDevice().info.describe() }
                .getOrElse { it.message ?: "No device selected." }
        },
        McpResource(
            uri = "android://project/application-id",
            name = "Project application ID",
            description = "Application ID of the app module in the open project.",
        ) { context ->
            context.projectApplicationId()
                ?: "No application ID could be resolved. Open an Android project and let Gradle sync finish."
        },
    )

    fun all(): List<McpResource> = resources

    fun find(uri: String): McpResource? = resources.firstOrNull { it.uri == uri }
}

class McpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "text/plain",
    private val reader: (ToolContext) -> String,
) {
    /** Prefixes the body with a read timestamp so staleness is always visible. */
    fun read(context: ToolContext): String {
        val body = runCatching { reader(context) }
            .getOrElse { "Could not read this resource: ${it.message}" }
        return "# read at ${java.time.Instant.now()}\n$body"
    }
}
