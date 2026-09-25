package spock.adb.uitree

import java.util.IdentityHashMap

/**
 * Where a node's reported bounds sit relative to what the capture covers.
 *
 * Being in the tree, being in the viewport and being fully on screen are three different things,
 * and none of them says the node is not covered by another: bounds cannot show occlusion.
 */
enum class Presence {
    /** Every reported pixel is inside the viewport and inside every scroll container above it. */
    IN_VIEWPORT,

    /** Some of it is; [NodeVisibility.visibleRegion] is that part. */
    PARTIALLY_IN_VIEWPORT,

    /** In the tree, but nothing of it is inside the viewport or its scroll container. */
    OUTSIDE_VIEWPORT,

    /** Reported with no area, so there is nothing of it anywhere to see or tap. */
    ZERO_AREA,

    /** The capture has no viewport (see [UiObservation.viewport]), so nothing was checked. */
    VIEWPORT_UNKNOWN,
}

/**
 * One node's [Presence], with the part of it in view.
 *
 * `uiautomator` reports a node partly scrolled out of its container with bounds already cut to
 * the part in view: a half-visible list row shows up whole-looking and smaller, its text child
 * missing. Such bounds reach the edge of the container, which is the only sign there is, so a
 * node whose bounds reach a scroll container's edge is [clippedByContainer]: its reported size is
 * not its real size, and how much of it is out of view is unknown. The same row at scroll offset
 * zero looks identical, so the flag means "may be cut", never "is cut".
 */
data class NodeVisibility(
    val presence: Presence,
    /** The reported bounds clipped to the viewport and every scroll container above; null when none is in view. */
    val visibleRegion: UiNode.Bounds?,
    /**
     * [visibleRegion]'s share of the reported bounds, 0 to 1. Null when unknown: no viewport, no
     * area, or [clippedByContainer], where the reported bounds are not the whole node.
     */
    val visibleFraction: Double?,
    /** The reported bounds reach a scroll container's edge, so they may already be cut; see the class notes. */
    val clippedByContainer: Boolean = false,
) {

    /** In or partly in the viewport: something of it can be tapped. */
    val inViewport: Boolean
        get() = presence == Presence.IN_VIEWPORT || presence == Presence.PARTIALLY_IN_VIEWPORT

    /** One phrase for a sentence about the node. Never claims it is fully visible or not covered. */
    fun describe(): String = when (presence) {
        Presence.IN_VIEWPORT -> if (clippedByContainer) {
            "within the viewport, but at the edge of its scroll container, so part of it may be scrolled out " +
                "and its bounds may be only the part in view (occlusion not checked)"
        } else {
            "within the viewport (occlusion not checked)"
        }
        Presence.PARTIALLY_IN_VIEWPORT -> when (val percent = percentInView()) {
            null ->
                "partly in the viewport, cut at its scroll container's edge; how much is out of view is " +
                    "unknown (occlusion not checked)"
            else -> "$percent% in the viewport (occlusion not checked)"
        }
        Presence.OUTSIDE_VIEWPORT -> "in the tree but outside the viewport or its scroll container"
        Presence.ZERO_AREA -> "in the tree with zero area, so nothing of it is on screen"
        Presence.VIEWPORT_UNKNOWN -> "in the tree; the viewport is unknown, so whether it is on screen was not checked"
    }

    /**
     * A few words for a node that is not plainly in the viewport, for a tree listing where every
     * node pays for its marker; null otherwise. An unknown viewport is null too: the capture's
     * limits already say so once, rather than once per node.
     */
    fun marker(): String? = when (presence) {
        Presence.IN_VIEWPORT -> "may be clipped by scroll container".takeIf { clippedByContainer }
        Presence.PARTIALLY_IN_VIEWPORT -> percentInView()?.let { "$it% in viewport" }
            ?: "partly in viewport, clipped by scroll container"
        Presence.OUTSIDE_VIEWPORT -> "outside viewport"
        Presence.ZERO_AREA -> "zero area"
        Presence.VIEWPORT_UNKNOWN -> null
    }

    /** Whole percent, never rounded to 0 or 100: part of it is in view, and part is not. */
    private fun percentInView(): Int? =
        visibleFraction?.let { (it * PERCENT).toInt().coerceIn(1, PERCENT - 1) }

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * Classifies every node of a capture against its viewport, in one top-down walk.
 *
 * The walk carries a clip rect: it starts as the viewport, and at each **scrollable** node it
 * narrows to that node's bounds, since a scroll container shows its content only within itself.
 * Other parents do not narrow it — a card or a row does not hide what overflows it the way a list
 * does, and treating every parent as a clip would call a slightly overhanging badge off screen.
 *
 * A clip edge can cut a node's reported bounds (see [NodeVisibility.clippedByContainer]) when it
 * is a scroll container's edge, or a viewport edge the window extends past. A window inside the
 * display is not cut by it, so an app bar at the top of the screen is not "clipped".
 */
object ViewportVisibility {

    fun classifyAll(observation: UiObservation): Map<UiNode, NodeVisibility> =
        classifyAll(observation.tree, observation.viewport)

    /** As [classifyAll] for an observation, for a caller holding only a tree and its viewport. */
    fun classifyAll(tree: UiTree, viewport: UiNode.Bounds?): Map<UiNode, NodeVisibility> {
        val result = IdentityHashMap<UiNode, NodeVisibility>()
        val root = tree.root ?: return result
        if (viewport == null) {
            root.flatten().forEach { result[it] = unknown(it) }
            return result
        }
        walk(root, Clip(viewport, windowCuts(root.bounds, viewport)), result)
        return result
    }

    /** [node]'s visibility in [observation]; one algorithm, so it always agrees with [classifyAll]. */
    fun of(observation: UiObservation, node: UiNode): NodeVisibility =
        requireNotNull(classifyAll(observation)[node]) { "The node is not in this observation's tree." }

    /** The area inside the viewport and every scroll container so far, and which of its edges can cut. */
    private data class Clip(val rect: UiNode.Bounds?, val cuts: Edges)

    private data class Edges(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean)

    private val ALL_EDGES = Edges(left = true, top = true, right = true, bottom = true)

    /** The viewport's edges that the window extends past, which only a window larger than the display has. */
    private fun windowCuts(window: UiNode.Bounds, viewport: UiNode.Bounds) = Edges(
        left = window.left < viewport.left,
        top = window.top < viewport.top,
        right = window.right > viewport.right,
        bottom = window.bottom > viewport.bottom,
    )

    private fun walk(node: UiNode, clip: Clip, result: MutableMap<UiNode, NodeVisibility>) {
        result[node] = classify(node.bounds, clip)
        val inner = if (node.scrollable) Clip(clip.rect?.intersect(node.bounds), ALL_EDGES) else clip
        node.children.forEach { walk(it, inner, result) }
    }

    private fun unknown(node: UiNode) = when {
        node.bounds.hasArea -> NodeVisibility(Presence.VIEWPORT_UNKNOWN, null, null)
        else -> NodeVisibility(Presence.ZERO_AREA, null, null)
    }

    private fun classify(bounds: UiNode.Bounds, clip: Clip): NodeVisibility {
        if (!bounds.hasArea) return NodeVisibility(Presence.ZERO_AREA, null, null)
        // A null clip rect is a scroll container that is itself out of view: nothing inside it shows.
        val region = clip.rect?.let(bounds::intersect)
            ?: return NodeVisibility(Presence.OUTSIDE_VIEWPORT, null, 0.0)

        val clipped = reachesCuttingEdge(bounds, clip.rect, clip.cuts)
        val presence = if (region == bounds) Presence.IN_VIEWPORT else Presence.PARTIALLY_IN_VIEWPORT
        val fraction = if (clipped) null else region.area() / bounds.area()
        return NodeVisibility(presence, region, fraction, clipped)
    }

    /**
     * Whether [bounds] reach a cutting edge of [clip]. Reaching both edges of one axis is spanning
     * it — a full-width row in a vertical list — not being cut on that axis.
     */
    private fun reachesCuttingEdge(bounds: UiNode.Bounds, clip: UiNode.Bounds, cuts: Edges): Boolean =
        axisCut(bounds.left <= clip.left, bounds.right >= clip.right, cuts.left, cuts.right) ||
            axisCut(bounds.top <= clip.top, bounds.bottom >= clip.bottom, cuts.top, cuts.bottom)

    private fun axisCut(atStart: Boolean, atEnd: Boolean, startCuts: Boolean, endCuts: Boolean): Boolean =
        atStart != atEnd && ((atStart && startCuts) || (atEnd && endCuts))

    private fun UiNode.Bounds.area(): Double = width.toDouble() * height
}
