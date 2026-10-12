package spock.adb.uitree

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Finds accessibility problems an agent can explain and a developer can fix in code.
 *
 * Every finding carries a Compose-level, a View-level and a Flutter-level suggestion, because the
 * fix differs entirely: Compose problems are solved with `Modifier.semantics`, Flutter's with a
 * `Semantics` widget, and neither with `android:contentDescription`.
 */
object AccessibilityAudit {

    data class Finding(
        val severity: Severity,
        val issue: String,
        val node: UiNode,
        val composeFix: String,
        val viewFix: String,
        val flutterFix: String,
    ) {
        /** The fix written for [framework]; Compose's wherever Compose may be. */
        fun fixFor(framework: UiFramework): String = when (framework) {
            UiFramework.VIEWS -> viewFix
            UiFramework.FLUTTER -> flutterFix
            UiFramework.COMPOSE, UiFramework.HYBRID, UiFramework.UNKNOWN -> composeFix
        }

        fun describe(framework: UiFramework): String = buildString {
            append(severity.marker).append(' ').append(issue).appendLine()
            append("    class:  ").append(node.className).appendLine()
            append("    bounds: ").append(node.bounds).appendLine()
            node.printableLabel.takeIf { it.isNotBlank() }?.let { append("    label:  ").append(it).appendLine() }
            append("    fix:    ")
            append(fixFor(framework))
        }
    }

    enum class Severity(val marker: String) {
        ERROR("✗"),
        WARNING("!"),

        /** Possibly a problem, and possibly an artefact of what the capture could see. */
        INFO("i"),
    }

    fun audit(observation: UiObservation): List<Finding> = audit(observation.tree, observation.viewport)

    /**
     * @param viewport what the capture covers; see [UiObservation.viewport]. Defaults to the
     *   window's own bounds, which is what an observation falls back to without a display size.
     */
    fun audit(tree: UiTree, viewport: UiNode.Bounds? = tree.root?.bounds): List<Finding> {
        val nodes = tree.nodes().filter { it.bounds.hasArea }.toList()
        val complete = nodes.filterNot(clippedIn(tree, viewport))
        return buildList {
            addAll(unlabelledInteractiveNodes(complete))
            addAll(tinyTouchTargets(complete, tree.densityDpi, cutPages(tree)))
            addAll(duplicateLabels(nodes))
            addAll(unlabelledImages(nodes))
        }
    }

    /**
     * Controls partly scrolled out of their container, which the size and label checks leave out.
     *
     * `uiautomator` cuts such a node's bounds to the part in view and drops a text child that is
     * out of view, so a half-visible list row looked both too small and unlabelled. A finding that
     * comes from clipping is not a finding about the app. Off-screen controls with complete
     * bounds are still checked: nothing about them was cut.
     */
    private fun clippedIn(tree: UiTree, viewport: UiNode.Bounds?): (UiNode) -> Boolean {
        val visibility = ViewportVisibility.classifyAll(tree, viewport)
        return { visibility[it]?.clippedByContainer == true }
    }

    /**
     * The nodes of Flutter carousel pages whose bounds are probably cut at the carousel's edge,
     * though nothing scrolls there.
     *
     * Flutter cuts a node's bounds at every clip above it, not only at scroll containers, and an
     * auto-advancing offer banner — a `PageView` with `NeverScrollableScrollPhysics` — publishes no
     * scroll action, so [ViewportVisibility] does not know it for a clip. The offers peeking in at
     * its sides are reported as slivers a few dp wide. Their size findings are still made, at
     * [Severity.INFO] and saying the bounds may be cut; the label check is not affected, since a
     * cut page keeps its whole merged content description.
     *
     * Only on a Flutter screen, and only this shape: at least three pages laid edge to edge along
     * one axis, filling the carousel exactly and spanning it across; the inner pages one size; the
     * two end pages one smaller size, each a non-interactive wrapper whose single child has its
     * exact bounds — which is how Flutter publishes a `PageView` page.
     */
    private fun cutPages(tree: UiTree): Set<UiNode> {
        val cut = Collections.newSetFromMap(IdentityHashMap<UiNode, Boolean>())
        if (tree.framework != UiFramework.FLUTTER) return cut
        tree.nodes().filterNot { it.scrollable }.forEach { carousel ->
            Axis.entries.flatMap { cutEnds(carousel, it) }.forEach { cut.addAll(it.flatten()) }
        }
        return cut
    }

    private fun cutEnds(carousel: UiNode, axis: Axis): List<UiNode> {
        if (carousel.children.size < MIN_PAGES) return emptyList()
        val pages = carousel.children.sortedBy { axis.start(it.bounds) }
        val tiled = axis.start(pages.first().bounds) == axis.start(carousel.bounds) &&
            axis.end(pages.last().bounds) == axis.end(carousel.bounds) &&
            pages.zipWithNext().all { (a, b) -> axis.end(a.bounds) == axis.start(b.bounds) } &&
            pages.all { axis.spansAcross(it.bounds, carousel.bounds) }
        val inner = pages.drop(1).dropLast(1).map { axis.extent(it.bounds) }.distinct().singleOrNull()
        val ends = listOf(pages.first(), pages.last())
        val endExtent = ends.map { axis.extent(it.bounds) }.distinct().singleOrNull()
        val cut = tiled && inner != null && endExtent != null && endExtent < inner && ends.all { it.isPageWrapper() }
        return if (cut) ends else emptyList()
    }

    /** Flutter's page: nothing to act on itself, and exactly one child filling it. */
    private fun UiNode.isPageWrapper(): Boolean = !isInteractive && children.singleOrNull()?.bounds == bounds

    private enum class Axis {
        HORIZONTAL,
        VERTICAL,
        ;

        fun start(b: UiNode.Bounds) = if (this == HORIZONTAL) b.left else b.top
        fun end(b: UiNode.Bounds) = if (this == HORIZONTAL) b.right else b.bottom
        fun extent(b: UiNode.Bounds) = end(b) - start(b)

        fun spansAcross(b: UiNode.Bounds, of: UiNode.Bounds) = if (this == HORIZONTAL) {
            b.top == of.top && b.bottom == of.bottom
        } else {
            b.left == of.left && b.right == of.right
        }
    }

    /** How many controls [audit] left out because they were cut off, and would otherwise have been findings. */
    private fun skippedAsClipped(tree: UiTree, viewport: UiNode.Bounds?): Int {
        val clipped = tree.nodes().filter { it.bounds.hasArea }.filter(clippedIn(tree, viewport)).toList()
        return clipped.count { node ->
            unlabelledInteractiveNodes(listOf(node)).isNotEmpty() ||
                tinyTouchTargets(listOf(node), tree.densityDpi).isNotEmpty()
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
                    flutterFix = "Wrap it in Semantics(label: '…'), or give an IconButton a tooltip. Add " +
                        "Semantics(identifier: '…') so it can also be addressed by automation.",
                )
            }

    /**
     * Bounds are an estimate: accessibility XML does not expose every expanded hit region.
     *
     * @param cut nodes whose bounds may be cut by a parent clip (see [cutPages]): reported at
     *   [Severity.INFO], saying so, rather than left out.
     */
    private fun tinyTouchTargets(nodes: List<UiNode>, densityDpi: Int?, cut: Set<UiNode> = emptySet()): List<Finding> {
        if (densityDpi == null || densityDpi <= 0) return emptyList()
        val minimumPx = MIN_TOUCH_DP * densityDpi / BASE_DENSITY
        return nodes.filter { it.clickable && (it.bounds.width < minimumPx || it.bounds.height < minimumPx) }
            .map {
                val maybeCut = it in cut
                Finding(
                    severity = if (maybeCut) Severity.INFO else Severity.WARNING,
                    issue = "Reported bounds are smaller than the recommended minimum of 48dp " +
                        "(${it.bounds.width}x${it.bounds.height}px at ${densityDpi}dpi); " +
                        if (maybeCut) CUT_NOTE else "verify the effective touch target",
                    node = it,
                    composeFix = "Use a minimum 48.dp touch target, for example IconButton, and verify " +
                        "any expanded touch area on the device.",
                    viewFix = "Use a minimum 48dp touch target and verify any TouchDelegate expansion.",
                    flutterFix = "Keep MaterialTapTargetSize.padded (the default) rather than shrinkWrap, or wrap " +
                        "the control in a SizedBox of at least 48x48, and verify on the device.",
                )
            }
    }

    fun coverageNote(observation: UiObservation): String = coverageNote(observation.tree, observation.viewport)

    fun coverageNote(tree: UiTree, viewport: UiNode.Bounds? = tree.root?.bounds): String = buildString {
        append("Checks use accessibility data and do not prove full accessibility or effective touch regions.")
        if (tree.densityDpi == null || tree.densityDpi <= 0) {
            append(" Display density unavailable; touch-target size check skipped.")
        } else {
            append(" Touch-target estimates use ${tree.densityDpi}dpi on the default display.")
        }
        val skipped = skippedAsClipped(tree, viewport)
        if (skipped > 0) {
            append(" Skipped $skipped control(s) at a scroll container's edge, possibly partially scrolled out, ")
            append("whose reported size or label may be cut off; they are checked when fully in view.")
        }
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
                    issue = "Several interactive elements share the label '${it.accessibleLabel.oneLine()}'",
                    node = it,
                    composeFix = "Give controls meaningful accessible labels or context " +
                        "that distinguishes their purpose. " +
                        "A test tag alone does not change what a screen reader announces.",
                    viewFix = "Give each view a distinct contentDescription.",
                    flutterFix = "Give each control a Semantics(label: '…') or tooltip that says what it acts on. " +
                        "Semantics(identifier:) alone does not change what a screen reader announces.",
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
                    flutterFix = "Pass semanticLabel to the Image or Icon. If it is purely decorative, wrap it " +
                        "in ExcludeSemantics.",
                )
            }

    private const val MIN_TOUCH_DP = 48.0
    private const val MIN_PAGES = 3
    private const val CUT_NOTE = "bounds may be cut by a parent clip (e.g. a carousel edge); verify on device"
    private const val BASE_DENSITY = 160.0
}
