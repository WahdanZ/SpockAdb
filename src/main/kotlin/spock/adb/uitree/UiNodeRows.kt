package spock.adb.uitree

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * One line describing a node, as "Copy Tree" writes it.
 *
 * The copied format is plain text a developer pastes into a bug or a chat, so it is kept exactly
 * as it was when the tree drew the same string; the tree now draws [rowSegments] instead.
 */
internal fun UiNode.describe(): String = buildString {
    append(className.substringAfterLast('.'))
    testTag?.let { append("  #").append(it) }
    text.takeIf { it.isNotBlank() }?.let { append("  \"").append(it).append('"') }
    contentDescription.takeIf { it.isNotBlank() }?.let { append("  desc=\"").append(it).append('"') }
    if (isInteractive) append("  ·")
    if (clickable) append(" clickable")
    if (scrollable) append(" scrollable")
    if (!enabled) append("  DISABLED")
}

/** The whole tree as indented [describe] lines, two spaces a level. */
internal fun renderTree(node: UiNode, depth: Int = 0): String = buildString {
    append("  ".repeat(depth)).append(node.describe()).append('\n')
    node.children.forEach { append(renderTree(it, depth + 1)) }
}

/** One styled piece of a tree row. */
internal data class RowSegment(val text: String, val kind: Kind) {
    enum class Kind { CLASS, TEST_TAG, TEXT, DESCRIPTION, FLAG }
}

/**
 * A node's tree row, as pieces the renderer styles one by one: the short class name, then the
 * test tag, the text and the content description, then what can be done with it.
 *
 * Text is cut to one short line. A paragraph of body copy drawn in full pushed every row after
 * it off the right edge; the whole value is in the details pane.
 */
internal fun UiNode.rowSegments(): List<RowSegment> = buildList {
    add(RowSegment(className.substringAfterLast('.'), RowSegment.Kind.CLASS))
    testTag?.let { add(RowSegment("#$it", RowSegment.Kind.TEST_TAG)) }
    text.takeIf { it.isNotBlank() }?.let { add(RowSegment("\"${it.clipped()}\"", RowSegment.Kind.TEXT)) }
    contentDescription.takeIf { it.isNotBlank() }?.let {
        add(RowSegment("desc=\"${it.clipped()}\"", RowSegment.Kind.DESCRIPTION))
    }
    flags().forEach { add(RowSegment(it, RowSegment.Kind.FLAG)) }
}

/** What an agent or a test can do with the node, and whether it currently can. */
private fun UiNode.flags(): List<String> = listOfNotNull(
    "clickable".takeIf { clickable },
    "long-clickable".takeIf { longClickable },
    "checkable".takeIf { checkable },
    "scrollable".takeIf { scrollable },
    "disabled".takeIf { !enabled },
)

private const val MAX_ROW_TEXT = 60

private fun String.clipped(): String {
    val oneLine = replace(Regex("\\s*\\n\\s*"), " ⏎ ")
    return if (oneLine.length <= MAX_ROW_TEXT) oneLine else oneLine.take(MAX_ROW_TEXT).trimEnd() + "…"
}

/**
 * What the search field and the "Interactive only" filter show: a flat list of visible nodes.
 *
 * Flat rather than a pruned hierarchy: a filtered tree that keeps the containers on the way to
 * each match is mostly containers, and hides what was found.
 */
internal fun inspectorMatches(tree: UiTree, query: String, interactiveOnly: Boolean): List<UiNode> =
    tree.nodes()
        .filter { it.bounds.isVisible }
        .filter { !interactiveOnly || it.isInteractive }
        .filter { node ->
            query.isBlank() ||
                node.text.contains(query, ignoreCase = true) ||
                node.contentDescription.contains(query, ignoreCase = true) ||
                node.testTag.orEmpty().contains(query, ignoreCase = true)
        }
        .toList()

/**
 * Draws a [UiNode] row with the IDE's own renderer, so selection, focus and the theme's
 * background are the tree's rather than a plain Swing label's grey block per row.
 */
internal class UiNodeRenderer : ColoredTreeCellRenderer() {
    @Suppress("LongParameterList")
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject
        val node = userObject as? UiNode ?: run {
            append(userObject?.toString().orEmpty(), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        // Device-supplied text, appended as fragments so it is never interpreted as markup.
        node.rowSegments().forEachIndexed { index, segment ->
            if (index > 0) append(if (segment.kind == RowSegment.Kind.FLAG) " " else "  ")
            append(segment.text, attributesFor(node, segment.kind))
        }
    }

    private fun attributesFor(node: UiNode, kind: RowSegment.Kind): SimpleTextAttributes = when {
        !node.enabled && kind != RowSegment.Kind.FLAG -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        else -> when (kind) {
            RowSegment.Kind.CLASS -> if (node.isInteractive) INTERACTIVE else SimpleTextAttributes.REGULAR_ATTRIBUTES
            RowSegment.Kind.TEST_TAG -> TAG
            RowSegment.Kind.TEXT -> TEXT
            RowSegment.Kind.DESCRIPTION -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            RowSegment.Kind.FLAG -> SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
        }
    }

    private companion object {
        val INTERACTIVE = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0x2C5D92, 0x6EA8E0))
        val TAG = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x871094, 0xC77DBB))
        val TEXT = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x067D17, 0x6AAB73))
    }
}
