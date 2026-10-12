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
    /**
     * What the activity history records in place of [content], when the two must differ: values
     * the developer let one agent see are not kept in a file every later reader of the panel can.
     */
    val historyText: String? = null,
) {
    /** The result as the activity history keeps it: [historyText], or the content with images described. */
    fun historyPreview(): String = historyText ?: content.joinToString("\n") { item ->
        when (item) {
            is ToolContent.Text -> item.text
            is ToolContent.Image -> "[${item.mimeType}, ${item.base64Data.length} base64 chars]"
        }
    }

    companion object {
        fun text(value: String) = ToolResult(listOf(ToolContent.Text(value)))

        fun image(base64Data: String, mimeType: String = "image/png") =
            ToolResult(listOf(ToolContent.Image(base64Data, mimeType)))

        fun error(message: String) = ToolResult(listOf(ToolContent.Text(message)), isError = true)
    }
}
