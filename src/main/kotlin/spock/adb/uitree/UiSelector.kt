package spock.adb.uitree

import java.util.Collections
import java.util.IdentityHashMap

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
    val packageName: String? = null,
    /** Exact, case-sensitive tag matching, opt-in for existing MCP clients. */
    val exactTag: Boolean = false,
    /** Restrict matches to one uniquely identified container subtree. */
    val containerTag: String? = null,
) {

    fun matches(node: UiNode): Boolean {
        if (isEmpty || !inScope(node)) return false
        if (interactiveOnly && !node.isInteractive) return false
        if (!node.bounds.isVisible) return false
        return tagMatches(node) && textMatches(node)
    }

    private fun inScope(node: UiNode): Boolean = packageName == null || node.packageName == packageName

    private fun tagMatches(node: UiNode): Boolean {
        val expected = testTag ?: return true
        return if (exactTag) {
            node.testTag == expected || node.resourceId == expected
        } else {
            compare(node.testTag.orEmpty(), expected)
        }
    }

    private fun textMatches(node: UiNode): Boolean = listOfNotNull(
        contentDescription?.let { node.contentDescription to it },
        text?.let { node.text to it },
    ).all { (actual, expected) -> compare(actual, expected) }

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
        packageName?.let { "packageName='$it'" },
        containerTag?.let { "containerTag='$it'" },
    ).joinToString(" and ").ifEmpty { "no criteria" }
}

object UiTreeSearch {

    private const val MAX_CANDIDATES = 5

    /**
     * All matches, most specific first.
     *
     * A node matched by test tag is ranked above one matched only by text, because the tag
     * was chosen deliberately while the text may coincide.
     */
    fun findAll(tree: UiTree, selector: UiSelector): List<UiNode> =
        searchRoot(tree, selector).asSequence()
            .filter(selector::matches)
            .sortedWith(matchRanking(selector))
            .toList()

    private fun searchRoot(tree: UiTree, selector: UiSelector): List<UiNode> {
        val tag = selector.containerTag ?: return tree.nodes().toList()
        val containers = tree.nodes().filter {
            (it.testTag == tag || it.resourceId == tag) &&
                (selector.packageName == null || it.packageName == selector.packageName)
        }.toList()
        require(containers.size == 1) {
            "Container '$tag' matched ${containers.size} nodes; give a unique container tag and packageName."
        }
        return containers.single().flatten()
    }

    /**
     * Mutating actions must never silently choose between matching controls.
     *
     * With an [action], matches are counted by the node that action would land on: a
     * clickable row and the icon inside it sharing a content description are one tap, not two.
     */
    fun findUnique(tree: UiTree, selector: UiSelector, action: Action? = null): UiNode? {
        val targets = Collections.newSetFromMap(IdentityHashMap<UiNode, Boolean>())
        val matches = findAll(tree, selector).filter { match ->
            targets.add(action?.let { eligibleTarget(tree, match, it) } ?: match)
        }
        require(matches.size <= 1) { ambiguityMessage(selector, matches) }
        return matches.singleOrNull()
    }

    private fun ambiguityMessage(selector: UiSelector, matches: List<UiNode>): String {
        // Lower-cased because `exact` compares case-insensitively and so cannot split "Save" from "SAVE".
        val indistinguishable = matches.map {
            listOf(it.text.lowercase(), it.contentDescription.lowercase(), it.resourceId, it.packageName)
        }.distinct().size == 1
        val advice = if (indistinguishable) {
            "These candidates have identical text, content description, resource ID and package, so no " +
                "selector field can tell them apart except containerTag, and only if they sit under " +
                "differently tagged containers. Otherwise the app needs a distinct test tag on each."
        } else {
            "Narrow it with exact: true (whole-value match), a unique testTag with exactTag: true, " +
                "packageName, or containerTag."
        }
        val candidates = matches.take(MAX_CANDIDATES).joinToString("; ") {
            "'${it.label}' ${it.className.substringAfterLast('.')} " +
                "id='${it.resourceId}' package='${it.packageName}' at ${it.bounds}"
        }
        return "Ambiguous selector ${selector.describe()}: ${matches.size} matches. $advice Candidates: $candidates"
    }

    /**
     * The one container a scroll should swipe.
     *
     * Nested scrollables — a feed of horizontal carousels, a pager holding a list — are resolved
     * to the outermost one, which is what the pre-scoping behaviour scrolled. Only unrelated
     * sibling containers are ambiguous.
     */
    fun scrollTarget(tree: UiTree, selector: UiSelector): UiNode? {
        val scope = searchRoot(tree, selector)
        val candidates = scope.filter {
            it.scrollable && it.enabled && it.bounds.isVisible &&
                (selector.packageName == null || it.packageName == selector.packageName)
        }
        if (selector.containerTag != null && candidates.any { it === scope.first() }) return scope.first()

        val outermost = candidates.filterNot { candidate ->
            candidates.any { other -> other !== candidate && isDescendant(candidate, of = other) }
        }
        require(outermost.size <= 1) {
            "Several scrollable containers matched; specify a unique containerTag and packageName."
        }
        return outermost.singleOrNull()
    }

    private fun isDescendant(node: UiNode, of: UiNode): Boolean = of.flatten().drop(1).any { it === node }

    enum class Action { TAP, LONG_PRESS, TEXT_INPUT }

    /** The nearest ancestor-or-self that [action] can land on, before any policy checks. */
    private fun eligibleTarget(tree: UiTree, node: UiNode, action: Action): UiNode? =
        (listOf(node) + ancestorsOf(tree, node)).firstOrNull {
            it.bounds.isVisible && when (action) {
                Action.TAP -> it.clickable
                Action.LONG_PRESS -> it.longClickable
                Action.TEXT_INPUT -> it.focusable && it.className.endsWith("EditText")
            }
        }

    fun actionTarget(
        tree: UiTree,
        node: UiNode,
        action: Action,
        selector: UiSelector = UiSelector(),
    ): UiNode {
        val target = eligibleTarget(tree, node, action)
        requireNotNull(target) { "Matched '${node.label}' has no eligible target for $action." }
        require(searchRoot(tree, selector).any { it === target }) { "Action target is outside the selected container." }
        require(target.enabled && node.enabled) { "Matched '${node.label}' is disabled." }
        require(target.packageName == node.packageName) { "Action target belongs to a different package." }
        return target
    }

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
