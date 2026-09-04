package spock.adb.mcp.tools

/** A single piece of MCP tool output. */
sealed interface ToolContent {

    data class Text(val text: String) : ToolContent

    /**
     * Binary content returned inline, base64 encoded.
     *
     * Screenshots are first-class output: an agent debugging a UI needs to see the screen,
     * not a description of it.
     */
    data class Image(val base64Data: String, val mimeType: String = "image/png") : ToolContent
}

data class ToolResult(
    val content: List<ToolContent>,
    val isError: Boolean = false,
) {
    companion object {
        fun text(value: String) = ToolResult(listOf(ToolContent.Text(value)))

        fun image(base64Data: String, mimeType: String = "image/png") =
            ToolResult(listOf(ToolContent.Image(base64Data, mimeType)))

        fun error(message: String) = ToolResult(listOf(ToolContent.Text(message)), isError = true)
    }
}
