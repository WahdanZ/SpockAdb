package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Being in the tree, in the viewport, and fully in view are different answers, and none of them
 * claims a node is not covered by another.
 */
class ViewportVisibilityTest {

    @Test
    fun `a node wholly inside the viewport is in it, all of it`() {
        val button = node(Bounds(100, 100, 300, 200))
        val seen = classify(window(button))[button]!!

        assertEquals(Presence.IN_VIEWPORT, seen.presence)
        assertEquals(button.bounds, seen.visibleRegion)
        assertEquals(1.0, seen.visibleFraction!!, EPSILON)
        assertFalse(seen.clippedByContainer)
        assertNull(seen.marker(), "a node plainly in view costs a tree listing nothing")
    }

    @Test
    fun `a node straddling the bottom edge is partly in, with the exact part and share`() {
        // It overhangs a window that fits on the display: no scroll container or display edge cut it.
        val panel = node(Bounds(0, 2200, 1080, 2600))
        val seen = classify(window(panel))[panel]!!

        assertEquals(Presence.PARTIALLY_IN_VIEWPORT, seen.presence)
        assertEquals(Bounds(0, 2200, 1080, HEIGHT), seen.visibleRegion)
        // Nothing could have cut its bounds, so they are the whole panel and the share is known.
        assertFalse(seen.clippedByContainer)
        assertEquals(0.5, seen.visibleFraction!!, EPSILON)
        assertEquals("50% in viewport", seen.marker())
        assertTrue(seen.describe().startsWith("50% in the viewport"), seen.describe())
    }

    @Test
    fun `a node below the display is outside it`() {
        val below = node(Bounds(0, 2500, 1080, 2700))
        val seen = classify(window(below, bounds = Bounds(0, 0, WIDTH, 3000)))[below]!!

        assertEquals(Presence.OUTSIDE_VIEWPORT, seen.presence)
        assertNull(seen.visibleRegion)
        assertEquals("outside viewport", seen.marker())
    }

    @Test
    fun `a node on the display but outside its scroll container is outside the viewport`() {
        // A 400px list near the top; its last row is laid out further down, over other content.
        val row = node(Bounds(0, 900, 1080, 1000))
        val list = node(Bounds(0, 300, 1080, 700), scrollable = true, children = listOf(row))
        val seen = classify(window(list))[row]!!

        assertEquals(Presence.OUTSIDE_VIEWPORT, seen.presence)
        assertEquals("in the tree but outside the viewport or its scroll container", seen.describe())
    }

    @Test
    fun `a parent that does not scroll does not clip its children`() {
        // A badge overhanging its card is still on screen: only a scroll container hides overflow.
        val badge = node(Bounds(900, 250, 1000, 350))
        val card = node(Bounds(0, 300, 1080, 700), children = listOf(badge))
        val seen = classify(window(card))[badge]!!

        assertEquals(Presence.IN_VIEWPORT, seen.presence)
        assertEquals(1.0, seen.visibleFraction!!, EPSILON)
    }

    @Test
    fun `a scroll container only clips what is inside it`() {
        val inside = node(Bounds(0, 400, 1080, 500))
        val list = node(Bounds(0, 300, 1080, 700), scrollable = true, children = listOf(inside))
        val below = node(Bounds(0, 900, 1080, 1000))
        val map = classify(window(list, below))

        assertEquals(Presence.IN_VIEWPORT, map[inside]!!.presence)
        assertEquals(Presence.IN_VIEWPORT, map[below]!!.presence, "a sibling of the list is not clipped by it")
    }

    @Test
    fun `nested scroll containers clip to their overlap`() {
        val card = node(Bounds(900, 400, 1200, 500))
        val carousel = node(Bounds(0, 400, 1080, 500), scrollable = true, children = listOf(card))
        val feed = node(Bounds(0, 300, 1080, 700), scrollable = true, children = listOf(carousel))
        val seen = classify(window(feed))[card]!!

        assertEquals(Presence.PARTIALLY_IN_VIEWPORT, seen.presence)
        assertEquals(Bounds(900, 400, 1080, 500), seen.visibleRegion)
    }

    @Test
    fun `a node with no area is zero area, whatever the viewport`() {
        val empty = node(Bounds(100, 100, 100, 100))

        assertEquals(Presence.ZERO_AREA, classify(window(empty))[empty]!!.presence)
        assertEquals(Presence.ZERO_AREA, classifyWithoutViewport(empty).getValue(empty).presence)
    }

    @Test
    fun `without a viewport nothing is claimed to be on screen or off it`() {
        val button = node(Bounds(100, 100, 300, 200))
        val seen = classifyWithoutViewport(button).getValue(button)

        assertEquals(Presence.VIEWPORT_UNKNOWN, seen.presence)
        assertNull(seen.visibleRegion)
        assertNull(seen.visibleFraction)
        assertFalse(seen.inViewport)
        assertNull(seen.marker(), "the limits line says it once, not once per node")
        assertTrue(seen.describe().contains("viewport is unknown"), seen.describe())
    }

    @Test
    fun `landscape uses the display's swapped dimensions`() {
        // Natural 1080x2400, turned a quarter: 2400 wide, 1080 tall. A node at y=1500 is below it.
        val right = node(Bounds(2000, 100, 2300, 200))
        val low = node(Bounds(100, 1500, 300, 1600))
        val root = window(right, low, bounds = Bounds(0, 0, HEIGHT, 2000))
        val map = ViewportVisibility.classifyAll(observation(root, rotation = 1))

        assertEquals(Presence.IN_VIEWPORT, map[right]!!.presence, "x=2300 fits in a 2400px-wide landscape display")
        assertEquals(Presence.OUTSIDE_VIEWPORT, map[low]!!.presence, "y=1500 is below a 1080px-tall one")
    }

    @Test
    fun `a row cut at its list's edge is flagged, its size unknown`() {
        // As uiautomator reports a row half scrolled out: bounds already cut to the list's bottom.
        val full = node(Bounds(72, 870, 1008, 1038), clickable = true)
        val cut = node(Bounds(72, 1542, 1008, 1590), clickable = true)
        val list = node(Bounds(72, 870, 1008, 1590), scrollable = true, children = listOf(full, cut))
        val map = classify(window(list))
        val seen = map[cut]!!

        assertEquals(Presence.IN_VIEWPORT, seen.presence, "all of what was reported is in view")
        assertTrue(seen.clippedByContainer)
        assertNull(seen.visibleFraction, "the reported bounds are not the whole row, so no share can be given")
        assertEquals(Bounds(72, 1542, 1008, 1590), seen.visibleRegion)
        assertEquals("may be clipped by scroll container", seen.marker())
        assertTrue(seen.describe().contains("may be scrolled out"), seen.describe())

        // Spanning the list's width is not being cut on that axis; reaching its top is.
        val middle = node(Bounds(72, 1038, 1008, 1206), clickable = true)
        val withMiddle = classify(window(list.copy(children = listOf(middle))))
        assertFalse(withMiddle[middle]!!.clippedByContainer, "a full-width row away from both ends is complete")
        assertTrue(map[full]!!.clippedByContainer, "the first row at scroll 0 looks the same as one scrolled past")
    }

    @Test
    fun `a row crossing its list's edge is partly in view and flagged`() {
        // Seen on an API 34 emulator: the last row's bounds ran past the list's bottom, yet were still cut.
        val row = node(Bounds(72, 1542, 1008, 1662), clickable = true)
        val list = node(Bounds(72, 870, 1008, 1590), scrollable = true, children = listOf(row))
        val seen = classify(window(list))[row]!!

        assertEquals(Presence.PARTIALLY_IN_VIEWPORT, seen.presence)
        assertEquals(Bounds(72, 1542, 1008, 1590), seen.visibleRegion)
        assertTrue(seen.clippedByContainer)
        assertEquals("partly in viewport, clipped by scroll container", seen.marker())
    }

    @Test
    fun `the display's own edges do not clip a window that fits on it`() {
        // An app bar at the top of the screen touches the viewport's edge; nothing was cut from it.
        val bar = node(Bounds(0, 0, WIDTH, 200))
        val seen = classify(window(bar))[bar]!!

        assertEquals(Presence.IN_VIEWPORT, seen.presence)
        assertFalse(seen.clippedByContainer)
    }

    @Test
    fun `one lookup agrees with the whole classification`() {
        val row = node(Bounds(0, 900, 1080, 1000))
        val list = node(Bounds(0, 300, 1080, 700), scrollable = true, children = listOf(row))
        val observation = observation(window(list))

        assertEquals(ViewportVisibility.classifyAll(observation)[row], ViewportVisibility.of(observation, row))
    }

    @Test
    fun `identical nodes are told apart by identity, not equality`() {
        // Equal in every field, but one is in its list's view and the other lies outside a second list.
        val inList = node(Bounds(0, 400, 1080, 500))
        val list = node(Bounds(0, 300, 1080, 700), scrollable = true, children = listOf(inList))
        val twin = inList.copy()
        val other = node(Bounds(0, 800, 1080, 900), scrollable = true, children = listOf(twin))
        assertEquals(inList, twin)
        val map = classify(window(list, other))

        assertEquals(Presence.IN_VIEWPORT, map[inList]!!.presence)
        assertEquals(Presence.OUTSIDE_VIEWPORT, map[twin]!!.presence)
    }

    @Test
    fun `no description or marker claims a node is fully visible or unobscured`() {
        val fractions = listOf(null, 0.0, 0.004, 0.5, 0.999, 1.0)
        val all = Presence.entries.flatMap { presence ->
            fractions.flatMap { fraction ->
                listOf(true, false).map { clipped ->
                    NodeVisibility(presence, Bounds(0, 0, 10, 10), fraction, clipped)
                }
            }
        }

        all.forEach { seen ->
            val text = seen.describe() + " " + seen.marker().orEmpty()
            assertFalse(text.contains("fully visible", ignoreCase = true), text)
            assertFalse(text.contains("unobscured", ignoreCase = true), text)
            assertFalse(text.contains("100%") || text.contains(" 0%"), "part in view is never rounded away: $text")
        }
        val partial = all.first { it.presence == Presence.PARTIALLY_IN_VIEWPORT && it.visibleFraction == 0.5 }
        assertNotNull(partial.marker())
        assertTrue(all.filter { it.inViewport }.all { it.describe().contains("occlusion not checked") })
    }

    private fun classify(root: UiNode) = ViewportVisibility.classifyAll(observation(root))

    private fun classifyWithoutViewport(child: UiNode): Map<UiNode, NodeVisibility> {
        // No display size, and a window with no area: nothing to call a viewport.
        val root = window(child, bounds = Bounds(0, 0, 0, 0))
        val observation = observation(root, metrics = DisplayMetrics.UNKNOWN)
        assertNull(observation.viewport)
        return ViewportVisibility.classifyAll(observation)
    }

    private fun observation(
        root: UiNode,
        rotation: Int? = 0,
        metrics: DisplayMetrics = DisplayMetrics(DENSITY, WIDTH, HEIGHT),
    ) = UiObservation(
        tree = UiTree(root, UiFramework.COMPOSE, UiTree.TestTagSupport.AVAILABLE, DENSITY, rotation),
        deviceSerial = "emulator-5554",
        startedAtMillis = 0,
        completedAtMillis = 0,
        metrics = metrics,
    )

    private fun window(vararg children: UiNode, bounds: Bounds = Bounds(0, 0, WIDTH, HEIGHT)) =
        node(bounds, children = children.toList())

    private fun node(
        bounds: Bounds,
        scrollable: Boolean = false,
        clickable: Boolean = false,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        className = "android.view.View",
        packageName = "p",
        text = "",
        contentDescription = "",
        resourceId = "",
        bounds = bounds,
        clickable = clickable,
        longClickable = false,
        enabled = true,
        focused = false,
        focusable = false,
        scrollable = scrollable,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = children,
    )

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
        const val DENSITY = 420
        const val EPSILON = 1e-9
    }
}

private typealias Bounds = UiNode.Bounds
