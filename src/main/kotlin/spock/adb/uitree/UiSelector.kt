package spock.adb.uitree

/**
 * Finds a node by what it *means* rather than where it is.
 *
 * Coordinates are the fallback, never the primary mechanism: a tap derived from a
 * screenshot breaks on a different screen size, density, font scale or after any layout
 * change, and is the main reason AI-driven UI automation is flaky. Matching on a test tag,
 * text or content description survives all of those.
 *
 * Match order is deliberate, most specific first:
 *
 *  1. **testTag** — an identifier the developer chose, stable across copy changes.
 *  2. **content description** — stable, and meaningful for accessibility.
 *  3. **text** — visible, but changes with translations and copy edits.
 */
data class UiSelector(
    val testTag: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    /** Substring rather than whole-string comparison. */
    val exact: Boolean = false,
    /** Restrict to nodes an agent can actually act on. */
    val interactiveOnly: Boolean = false,
) {

    fun matches(node: UiNode): Boolean {
        if (interactiveOnly && !node.isInteractive) return false
        if (!node.bounds.isVisible) return false

        val criteria = listOfNotNull(
            testTag?.let { node.testTag.orEmpty() to it },
            contentDescription?.let { node.contentDescription to it },
            text?.let { node.text to it },
        )
        if (criteria.isEmpty()) return false

        return criteria.all { (actual, expected) -> compare(actual, expected) }
    }

    private fun compare(actual: String, expected: String): Boolean = when {
        exact -> actual.equals(expected, ignoreCase = true)
        else -> actual.contains(expected, ignoreCase = true)
    }

    val isEmpty: Boolean get() = testTag == null && text == null && contentDescription == null

    /** A short description of what was searched for, for error messages. */
    fun describe(): String = listOfNotNull(
        testTag?.let { "testTag='$it'" },
        text?.let { "text='$it'" },
        contentDescription?.let { "contentDescription='$it'" },
    ).joinToString(" and ").ifEmpty { "no criteria" }
}

object UiTreeSearch {

    /**
     * All matches, most specific first.
     *
     * A node matched by test tag is ranked above one matched only by text, because the tag
     * was chosen deliberately while the text may coincide.
     */
    fun findAll(tree: UiTree, selector: UiSelector): List<UiNode> =
        tree.nodes()
            .filter(selector::matches)
            .sortedWith(matchRanking(selector))
            .toList()

    private fun matchRanking(selector: UiSelector): Comparator<UiNode> =
        compareByDescending<UiNode> { it.testTag != null && selector.testTag != null }
            .thenByDescending { it.isInteractive }
            .thenBy { it.bounds.top }
            .thenBy { it.bounds.left }

    fun findOne(tree: UiTree, selector: UiSelector): UiNode? = findAll(tree, selector).firstOrNull()

    /**
     * The nearest interactive ancestor-or-self.
     *
     * Compose commonly puts the text on a child node and the click handler on its parent, so
     * a match on visible text often is not the thing that can be tapped. Walking up to the
     * clickable node is what makes "tap the Continue button" work in Compose.
     */
    fun interactiveTarget(tree: UiTree, node: UiNode): UiNode {
        if (node.isInteractive) return node
        val ancestors = ancestorsOf(tree, node)
        return ancestors.firstOrNull { it.isInteractive } ?: node
    }

    /** Ancestors of [node], closest first. */
    private fun ancestorsOf(tree: UiTree, node: UiNode): List<UiNode> {
        val path = mutableListOf<UiNode>()
        fun walk(current: UiNode, trail: List<UiNode>): Boolean {
            if (current === node) {
                path += trail.asReversed()
                return true
            }
            return current.children.any { walk(it, trail + current) }
        }
        tree.root?.let { walk(it, emptyList()) }
        return path
    }
}
