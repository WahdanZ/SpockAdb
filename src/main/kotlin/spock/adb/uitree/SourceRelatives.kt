package spock.adb.uitree

/**
 * An element near the selected one, searched for in its place when the selected one finds nothing.
 *
 * In Compose most rows carry nothing of their own to search for: a `Button`'s label is a child
 * `Text`, and a `Column` or `Row` has neither tag nor text. Their nearest identifiable relative
 * usually comes from the same place in the source — the label is written inside the button's call,
 * the row inside the tagged section — so it stands in, and the Source line says whose identifier
 * found it.
 */
internal data class SourceRelative(val node: UiNode, val kind: Kind, val query: SourceQuery) {

    enum class Kind {
        /** The only text inside the element: a button's label. */
        LABEL,

        /** Another identifiable element inside it. */
        DESCENDANT,

        /** The nearest identifiable element around it. */
        ANCESTOR,
    }

    /** Finishes "found by test tag …", as "via its label 'Save'" or "via enclosing 'feed_section'". */
    val description: String
        get() = when (kind) {
            Kind.LABEL -> "via its label '$name'"
            Kind.DESCENDANT -> "via '$name' inside it"
            Kind.ANCESTOR -> "via enclosing '$name'"
        }

    private val name: String
        get() = node.label.ifBlank { node.className.substringAfterLast('.') }
            .replace('\n', ' ')
            .let { if (it.length <= NAME_MAX) it else it.take(NAME_MAX - 1) + "…" }

    private companion object {
        const val NAME_MAX = 40
    }
}

internal object SourceRelatives {

    /** How far below the element to look: a button's label is one level down, a card's title two or three. */
    const val MAX_DEPTH = 3
    const val MAX_DESCENDANTS = 4
    const val MAX_ANCESTORS = 3

    /**
     * The relatives to search for, in order, when [node] itself finds nothing: first what is inside
     * it, breadth first and at most [MAX_DEPTH] levels down — its label first when it has exactly
     * one text inside and none of its own — then what encloses it, nearest first. Only relatives
     * with something to search for are listed, at most [MAX_DESCENDANTS] and [MAX_ANCESTORS] of each.
     *
     * Nodes are told apart by identity: a list's identical rows are equal as data.
     */
    fun of(tree: UiTree?, node: UiNode): List<SourceRelative> {
        val framework = tree?.framework ?: UiFramework.UNKNOWN
        val below = descendants(node).mapNotNull { it.identified(framework) }
        val label = below.filter { it.first.text.isNotBlank() }.singleOrNull()?.takeIf { node.text.isBlank() }
        val inside = listOfNotNull(label?.let { SourceRelative(it.first, SourceRelative.Kind.LABEL, it.second) }) +
            below.filterNot { it === label }.map { SourceRelative(it.first, SourceRelative.Kind.DESCENDANT, it.second) }
        val around = ancestors(tree?.root, node)
            .mapNotNull { it.identified(framework) }
            .take(MAX_ANCESTORS)
            .map { SourceRelative(it.first, SourceRelative.Kind.ANCESTOR, it.second) }
        return inside.take(MAX_DESCENDANTS) + around
    }

    private fun UiNode.identified(framework: UiFramework): Pair<UiNode, SourceQuery>? =
        SourceQuery.of(this, framework).takeUnless { it.isEmpty }?.let { this to it }

    /** Everything under [node] down to [MAX_DEPTH] levels, breadth first, [node] itself left out. */
    private fun descendants(node: UiNode): List<UiNode> {
        val found = ArrayList<UiNode>()
        var level = node.children
        repeat(MAX_DEPTH) {
            found += level
            level = level.flatMap { it.children }
        }
        return found
    }

    /** What encloses [node] in the tree under [root], nearest first; empty when it is not in that tree. */
    private fun ancestors(root: UiNode?, node: UiNode): List<UiNode> {
        val path = ArrayList<UiNode>()
        fun walk(current: UiNode): Boolean {
            if (current === node) return true
            path += current
            if (current.children.any { walk(it) }) return true
            path.removeAt(path.lastIndex)
            return false
        }
        return if (root != null && walk(root)) path.asReversed() else emptyList()
    }
}
