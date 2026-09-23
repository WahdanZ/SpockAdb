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
            addAll(tinyTouchTargets(nodes, tree.densityDpi))
            addAll(duplicateLabels(nodes))
            addAll(unlabelledImages(nodes))
        }
    }

    /**
     * A control a screen reader cannot announce, and an agent cannot address semantically.
     *
     * Scrolling alone does not count: a list is announced through its rows, and Android's
     * guidance does not ask a bare scroll container for its own label. A scrollable node that
     * is also clickable, long-clickable or checkable is still checked.
     */
    private fun unlabelledInteractiveNodes(nodes: List<UiNode>): List<Finding> =
        nodes.filter { (it.clickable || it.longClickable || it.checkable) && it.accessibleLabel.isBlank() }
            .map {
                Finding(
                    severity = Severity.ERROR,
                    issue = "Interactive element has no accessible text or content description",
                    node = it,
                    composeFix = "Modifier.semantics { contentDescription = \"…\" } — or give the " +
                        "composable visible text. Add Modifier.testTag(\"…\") so it can also be " +
                        "addressed by automation.",
                    viewFix = "android:contentDescription=\"…\" on the view.",
                )
            }

    /** Bounds are an estimate: accessibility XML does not expose every expanded hit region. */
    private fun tinyTouchTargets(nodes: List<UiNode>, densityDpi: Int?): List<Finding> {
        if (densityDpi == null || densityDpi <= 0) return emptyList()
        val minimumPx = MIN_TOUCH_DP * densityDpi / BASE_DENSITY
        return nodes.filter { it.clickable && (it.bounds.width < minimumPx || it.bounds.height < minimumPx) }
            .map {
                Finding(
                    severity = Severity.WARNING,
                    issue = "Reported bounds are smaller than the recommended minimum of 48dp " +
                        "(${it.bounds.width}x${it.bounds.height}px at ${densityDpi}dpi); " +
                        "verify the effective touch target",
                    node = it,
                    composeFix = "Use a minimum 48.dp touch target, for example IconButton, and verify " +
                        "any expanded touch area on the device.",
                    viewFix = "Use a minimum 48dp touch target and verify any TouchDelegate expansion.",
                )
            }
    }

    fun coverageNote(tree: UiTree): String =
        "Checks use accessibility data and do not prove full accessibility or effective touch regions." +
            if (tree.densityDpi == null || tree.densityDpi <= 0) {
                " Display density unavailable; touch-target size check skipped."
            } else {
                " Touch-target estimates use ${tree.densityDpi}dpi on the default display."
            }

    /** Two controls announcing the same thing are ambiguous to a reader and to an agent. */
    private fun duplicateLabels(nodes: List<UiNode>): List<Finding> =
        nodes.filter { it.isInteractive && it.accessibleLabel.isNotBlank() }
            .groupBy { it.accessibleLabel.lowercase() }
            .filterValues { it.size > 1 }
            .values
            .flatten()
            .map {
                Finding(
                    severity = Severity.WARNING,
                    issue = "Several interactive elements share the label '${it.accessibleLabel}'",
                    node = it,
                    composeFix = "Give controls meaningful accessible labels or context " +
                        "that distinguishes their purpose. " +
                        "A test tag alone does not change what a screen reader announces.",
                    viewFix = "Give each view a distinct contentDescription.",
                )
            }

    /** A meaningful image with nothing to announce. */
    private fun unlabelledImages(nodes: List<UiNode>): List<Finding> =
        nodes.filter { it.className.contains("Image") && it.accessibleLabel.isBlank() && !it.isInteractive }
            .map {
                Finding(
                    severity = Severity.WARNING,
                    issue = "Image has no accessible label; check whether it is decorative",
                    node = it,
                    composeFix = "Pass contentDescription to Image(). If it is purely " +
                        "decorative, pass null explicitly so the intent is recorded.",
                    viewFix = "android:contentDescription, or " +
                        "android:importantForAccessibility=\"no\" when decorative.",
                )
            }

    private const val MIN_TOUCH_DP = 48.0
    private const val BASE_DENSITY = 160.0
}
