package spock.adb.uitree

/**
 * Finds accessibility problems an agent can explain and a developer can fix in code.
 *
 * Every finding carries a Compose-level suggestion as well as a View-level one, because the
 * fix differs entirely: Compose problems are solved with `Modifier.semantics`, not with
 * `android:contentDescription`.
 */
object AccessibilityAudit {

    data class Finding(
        val severity: Severity,
        val issue: String,
        val node: UiNode,
        val composeFix: String,
        val viewFix: String,
    ) {
        fun describe(framework: UiFramework): String = buildString {
            append(severity.marker).append(' ').append(issue).appendLine()
            append("    class:  ").append(node.className).appendLine()
            append("    bounds: ").append(node.bounds).appendLine()
            node.label.takeIf { it.isNotBlank() }?.let { append("    label:  ").append(it).appendLine() }
            append("    fix:    ")
            append(if (framework == UiFramework.VIEWS) viewFix else composeFix)
        }
    }

    enum class Severity(val marker: String) {
        ERROR("✗"),
        WARNING("!"),
    }

    fun audit(tree: UiTree): List<Finding> {
        val nodes = tree.nodes().filter { it.bounds.isVisible }.toList()
        return buildList {
            addAll(unlabelledInteractiveNodes(nodes))
            addAll(tinyTouchTargets(nodes))
            addAll(duplicateLabels(nodes))
            addAll(unlabelledImages(nodes))
        }
    }

    /** A control a screen reader cannot announce, and an agent cannot address semantically. */
    private fun unlabelledInteractiveNodes(nodes: List<UiNode>): List<Finding> =
        nodes.filter { it.isInteractive && it.label.isBlank() }
            .map {
                Finding(
                    severity = Severity.ERROR,
                    issue = "Interactive element has no text, content description or test tag",
                    node = it,
                    composeFix = "Modifier.semantics { contentDescription = \"…\" } — or give the " +
                        "composable visible text. Add Modifier.testTag(\"…\") so it can also be " +
                        "addressed by automation.",
                    viewFix = "android:contentDescription=\"…\" on the view.",
                )
            }

    /**
     * Below the 48dp minimum from the Material accessibility guidance.
     *
     * Compared in pixels because that is all the dump provides; the threshold is generous
     * enough that it does not fire on ordinary densities for a correctly sized target.
     */
    private fun tinyTouchTargets(nodes: List<UiNode>): List<Finding> =
        nodes.filter { it.clickable && (it.bounds.width < MIN_TOUCH_PX || it.bounds.height < MIN_TOUCH_PX) }
            .map {
                Finding(
                    severity = Severity.WARNING,
                    issue = "Touch target is smaller than the recommended minimum " +
                        "(${it.bounds.width}x${it.bounds.height}px)",
                    node = it,
                    composeFix = "Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), or use " +
                        "IconButton which applies the minimum touch target for you.",
                    viewFix = "Increase the view's size or padding to at least 48dp.",
                )
            }

    /** Two controls announcing the same thing are ambiguous to a reader and to an agent. */
    private fun duplicateLabels(nodes: List<UiNode>): List<Finding> =
        nodes.filter { it.isInteractive && it.label.isNotBlank() }
            .groupBy { it.label.lowercase() }
            .filterValues { it.size > 1 }
            .values
            .flatten()
            .map {
                Finding(
                    severity = Severity.WARNING,
                    issue = "Several interactive elements share the label '${it.label}'",
                    node = it,
                    composeFix = "Give each a distinct contentDescription, or a unique " +
                        "Modifier.testTag(\"…\") so automation can tell them apart.",
                    viewFix = "Give each view a distinct contentDescription.",
                )
            }

    /** A meaningful image with nothing to announce. */
    private fun unlabelledImages(nodes: List<UiNode>): List<Finding> =
        nodes.filter { it.className.contains("Image") && it.label.isBlank() && !it.isInteractive }
            .map {
                Finding(
                    severity = Severity.WARNING,
                    issue = "Image has no content description",
                    node = it,
                    composeFix = "Pass contentDescription to Image(). If it is purely " +
                        "decorative, pass null explicitly so the intent is recorded.",
                    viewFix = "android:contentDescription, or " +
                        "android:importantForAccessibility=\"no\" when decorative.",
                )
            }

    private const val MIN_TOUCH_PX = 48
}
