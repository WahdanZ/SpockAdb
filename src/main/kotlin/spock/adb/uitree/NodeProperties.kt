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

    const val NONE = "—"
    private const val BASELINE_DPI = 160.0

    /**
     * @param visibility where [node] is relative to the capture's viewport, when that was worked out.
     * @param tree the capture [node] is from, for how many other elements share each identifier.
     */
    fun of(
        node: UiNode,
        densityDpi: Int?,
        visibility: NodeVisibility? = null,
        tree: UiTree? = null,
    ): List<PropertySection> = listOf(
        PropertySection("Identity", identity(node, tree)),
        PropertySection(
            "Geometry",
            listOf(
                row("Bounds", node.bounds.toString()),
                row("Size", "${node.bounds.width} × ${node.bounds.height} px"),
                row("Size in dp", dpSize(node.bounds.width, node.bounds.height, densityDpi)),
                row("Centre", "${node.bounds.centerX}, ${node.bounds.centerY}"),
            ) + listOfNotNull(visibility?.let { row("Viewport", viewport(it)) }),
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
                row("Password field", yesNo(node.password)),
            ),
        ),
    )

    /**
     * What the element is and what it can be found by, each identifier with how many elements on
     * the captured screen share it and whether the suggested selector uses it: a text that is
     * on three buttons identifies none of them.
     */
    private fun identity(node: UiNode, tree: UiTree?): List<PropertySection.Property> {
        val framework = tree?.framework ?: UiFramework.UNKNOWN
        val basis = SelectorSuggestion.forNode(node, framework)?.basis
        val nodes = tree?.nodes()?.toList()

        fun identifier(name: String, value: String, uses: SelectorSuggestion.Basis, of: (UiNode) -> String?) =
            if (value.isBlank()) {
                row(name, value)
            } else {
                row(name, value + notes(nodes?.count { of(it) == value }, basis == uses))
            }

        // On a Views screen the resource id is the View id; calling it a test tag would mislead.
        val tagName = if (framework == UiFramework.VIEWS) "View id" else "Test tag"
        val spoken = node.accessibleLabel.takeIf { it.isNotBlank() && it != node.text && it != node.contentDescription }
        return listOfNotNull(
            row("Class", classText(node.className)),
            identifier(tagName, node.testTag.orEmpty(), SelectorSuggestion.Basis.TEST_TAG) { it.testTag },
            identifier("Text", node.text, SelectorSuggestion.Basis.TEXT) { it.text },
            identifier("Content description", node.contentDescription, SelectorSuggestion.Basis.CONTENT_DESCRIPTION) {
                it.contentDescription
            },
            spoken?.let { row("Accessible label", it) },
            row("Resource id", node.resourceId),
            row("Package", node.packageName),
            row("Children", node.children.size.toString()),
        )
    }

    /** `Button · android.widget.Button`: the short name to scan by, the full one to search for. */
    fun classText(className: String): String {
        val short = className.substringAfterLast('.')
        return if (short == className || short.isBlank()) className else "$short · $className"
    }

    /** ` · unique on screen · selector`, ` · 3 on screen`, or nothing when the screen is unknown. */
    fun notes(count: Int?, usedBySelector: Boolean): String = buildString {
        when {
            count == null -> Unit
            count <= 1 -> append(" · unique on screen")
            else -> append(" · $count on screen")
        }
        if (usedBySelector) append(" · selector")
    }

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

    /** The description, starting with a capital as the other values in the table do. */
    private fun viewport(visibility: NodeVisibility): String =
        visibility.describe().replaceFirstChar { it.uppercaseChar() }

    private fun row(name: String, value: String) = PropertySection.Property(name, value.ifBlank { NONE })

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** `yes, checked` / `yes, not checked` / `no`: the state is meaningless without the capability. */
    private fun pair(capable: Boolean, state: Boolean, on: String, off: String) =
        if (capable) "yes, ${if (state) on else off}" else "no"
}
