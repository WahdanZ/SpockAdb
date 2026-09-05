package spock.adb.mcp

import spock.adb.mcp.tools.ToolContent
import spock.adb.mcp.tools.ToolResult

/** The text of a tool result, which is what almost every assertion here is about. */
fun ToolResult.text(): String =
    content.filterIsInstance<ToolContent.Text>().joinToString("\n") { it.text }
