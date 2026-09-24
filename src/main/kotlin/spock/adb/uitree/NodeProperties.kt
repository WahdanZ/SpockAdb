package spock.adb.uitree

import java.util.Locale

/** A titled group of a node's properties, in the order the details pane shows them. */
internal data class PropertySection(val title: String, val rows: List<Property>) {
    data class Property(val name: String, val value: String)
}

/**
 * A node's properties for the details pane: what it is, where it is, and what state it is in.
 *
 * Sizes are given in dp as well as px, because dp is what the layout code the developer is about
 * to open is written in. That takes the capture's density; when it could not be read the dp
 * value says so instead of guessing the common 160.
 */
internal object NodeProperties {

    const val NOT_INTERACTIVE_HINT =
        "Not interactive. In Compose the click handler usually sits on an ancestor, " +
            "so the tappable element may be this node's parent."

    private const val NONE = "—"
    private const val BASELINE_DPI = 160.0

    fun of(node: UiNode, densityDpi: Int?): List<PropertySection> = listOf(
        PropertySection(
            "Identity",
            listOf(
                row("Class", node.className),
                row("Test tag", node.testTag.orEmpty()),
                row("Text", node.text),
                row("Content description", node.contentDescription),
                row("Resource id", node.resourceId),
                row("Children", node.children.size.toString()),
            ),
        ),
        PropertySection(
            "Geometry",
            listOf(
                row("Bounds", node.bounds.toString()),
                row("Size", "${node.bounds.width} × ${node.bounds.height} px"),
                row("Size in dp", dpSize(node.bounds.width, node.bounds.height, densityDpi)),
                row("Centre", "${node.bounds.centerX}, ${node.bounds.centerY}"),
            ),
        ),
        PropertySection(
            "State",
            listOf(
                row("Enabled", yesNo(node.enabled)),
                row("Clickable", yesNo(node.clickable)),
                row("Long-clickable", yesNo(node.longClickable)),
                row("Scrollable", yesNo(node.scrollable)),
                row("Focusable", pair(node.focusable, node.focused, "focused", "not focused")),
                row("Checkable", pair(node.checkable, node.checked, "checked", "not checked")),
                row("Selected", yesNo(node.selected)),
            ),
        ),
    )

    /** [px] in dp at [densityDpi], or null when the density is unknown or not a density. */
    fun pxToDp(px: Int, densityDpi: Int?): Double? =
        densityDpi?.takeIf { it > 0 }?.let { px * BASELINE_DPI / it }

    /** `411.4 × 914.3 dp`, or why there is no dp size. */
    fun dpSize(widthPx: Int, heightPx: Int, densityDpi: Int?): String {
        val width = pxToDp(widthPx, densityDpi) ?: return "unknown: display density could not be read"
        val height = pxToDp(heightPx, densityDpi) ?: return "unknown: display density could not be read"
        return "${formatDp(width)} × ${formatDp(height)} dp at $densityDpi dpi"
    }

    /** One decimal place, without a trailing `.0`: `48`, `50.3`. */
    fun formatDp(dp: Double): String = String.format(Locale.ROOT, "%.1f", dp).removeSuffix(".0")

    private fun row(name: String, value: String) = PropertySection.Property(name, value.ifBlank { NONE })

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** `yes, checked` / `yes, not checked` / `no`: the state is meaningless without the capability. */
    private fun pair(capable: Boolean, state: Boolean, on: String, off: String) =
        if (capable) "yes, ${if (state) on else off}" else "no"
}
