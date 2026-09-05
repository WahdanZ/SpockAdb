package spock.adb.assistant

import spock.adb.mcp.tools.AdbTool

/**
 * The one mapping from a registered tool to the shape a model is told about.
 *
 * There is no `provider` parameter, deliberately, though the plan named one. A tool is a name,
 * a description and a JSON Schema in both providers' APIs; only the envelope around it differs,
 * and that envelope belongs to the client that writes the request body. Passing the provider in
 * here would put two providers' knowledge in the mapping and still leave each client shaping
 * its own request — the definition would be written twice, which is the thing this avoids.
 */
fun AdbTool.toToolSpec(): ToolSpec = ToolSpec(
    name = name,
    description = description,
    inputSchema = inputSchema,
)

fun Iterable<AdbTool>.toToolSpecs(): List<ToolSpec> = map { it.toToolSpec() }
