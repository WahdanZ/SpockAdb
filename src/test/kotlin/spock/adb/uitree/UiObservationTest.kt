package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.uitree.UiTree.TestTagSupport
import java.time.Instant

class UiObservationTest {

    @Test
    fun `the window is the package of the dumped root, whatever app is underneath`() {
        assertEquals("com.android.systemui", observe(root = window(pkg = "com.android.systemui")).windowPackage)
        assertNull(observe(root = window(pkg = "")).windowPackage)
        assertNull(observe(root = null).windowPackage)
    }

    @Test
    fun `a quarter turn swaps the natural size, a half turn does not`() {
        val portrait = UiNode.Bounds(0, 0, WIDTH, HEIGHT)
        val landscape = UiNode.Bounds(0, 0, HEIGHT, WIDTH)

        assertEquals(portrait, observe(rotation = 0).displayRect)
        assertEquals(landscape, observe(rotation = 1).displayRect)
        assertEquals(portrait, observe(rotation = 2).displayRect)
        assertEquals(landscape, observe(rotation = 3).displayRect)
        assertNull(observe(rotation = null).displayRect, "a size without its rotation cannot be oriented")
    }

    @Test
    fun `the viewport is the window clipped to the display`() {
        // A dialog's window is smaller than the display; a window reported past the edge is clipped.
        val dialog = UiNode.Bounds(100, 600, 980, 1700)
        assertEquals(dialog, observe(root = window(bounds = dialog)).viewport)

        val overhanging = UiNode.Bounds(-10, 0, WIDTH + 10, HEIGHT + 200)
        assertEquals(UiNode.Bounds(0, 0, WIDTH, HEIGHT), observe(root = window(bounds = overhanging)).viewport)
    }

    @Test
    fun `what a capture cannot show is always disclosed`() {
        val limits = observe().limits

        assertTrue(ObservationLimit.ACCESSIBILITY_TREE_ONLY in limits, limits.toString())
        assertTrue(ObservationLimit.NO_OCCLUSION in limits, limits.toString())
    }

    @Test
    fun `unobserved test tags are a limit only when tags were looked for and not found`() {
        assertTrue(ObservationLimit.TEST_TAGS_NOT_OBSERVED in observe(tags = TestTagSupport.UNAVAILABLE).limits)
        assertFalse(ObservationLimit.TEST_TAGS_NOT_OBSERVED in observe(tags = TestTagSupport.AVAILABLE).limits)
        assertFalse(ObservationLimit.TEST_TAGS_NOT_OBSERVED in observe(tags = TestTagSupport.NOT_APPLICABLE).limits)
    }

    @Test
    fun `exposed tags on a Compose screen disclose the AndroidView gap`() {
        val compose = observe(framework = UiFramework.COMPOSE, tags = TestTagSupport.AVAILABLE)
        val hybrid = observe(framework = UiFramework.HYBRID, tags = TestTagSupport.AVAILABLE)
        val views = observe(framework = UiFramework.VIEWS, tags = TestTagSupport.NOT_APPLICABLE)

        assertTrue(ObservationLimit.INTEROP_TAG_PROVENANCE in compose.limits)
        assertTrue(ObservationLimit.INTEROP_TAG_PROVENANCE in hybrid.limits)
        assertFalse(ObservationLimit.INTEROP_TAG_PROVENANCE in views.limits)
    }

    @Test
    fun `an unknown density is a limit, and only then`() {
        assertTrue(ObservationLimit.DENSITY_UNKNOWN in observe(density = null).limits)
        assertFalse(ObservationLimit.DENSITY_UNKNOWN in observe(density = DENSITY).limits)
        assertEquals(DENSITY, observe(density = DENSITY).densityDpi)
    }

    @Test
    fun `without the display size the viewport is the window, and says so`() {
        val observation = observe(metrics = DisplayMetrics.UNKNOWN)

        assertEquals(FULL_SCREEN, observation.viewport)
        assertTrue(ObservationLimit.DISPLAY_SIZE_UNKNOWN in observation.limits)
        assertFalse(ObservationLimit.VIEWPORT_UNKNOWN in observation.limits)
    }

    @Test
    fun `no window and no display size is no viewport at all`() {
        val observation = observe(root = null, metrics = DisplayMetrics.UNKNOWN)

        assertNull(observation.viewport)
        assertTrue(ObservationLimit.VIEWPORT_UNKNOWN in observation.limits)
        assertFalse(ObservationLimit.DISPLAY_SIZE_UNKNOWN in observation.limits)
    }

    @Test
    fun `a fully measured capture has only the limits every capture has`() {
        // A Views screen: on Compose with exposed tags the AndroidView gap is disclosed as well.
        assertEquals(
            listOf(ObservationLimit.ACCESSIBILITY_TREE_ONLY, ObservationLimit.NO_OCCLUSION),
            observe(framework = UiFramework.VIEWS, tags = TestTagSupport.NOT_APPLICABLE).limits,
        )
    }

    @Test
    fun `the summary names the device, the window, an instant on the host clock, and the viewport`() {
        val summary = observe(started = STARTED, completed = STARTED + 1_800).summary()

        assertTrue(summary.startsWith("Observed on $SERIAL, window $PACKAGE, "), summary)
        assertTrue(summary.contains(Instant.ofEpochMilli(STARTED).toString()), summary)
        assertTrue(summary.contains("host clock, 1.8 s capture"), summary)
        assertTrue(summary.contains("viewport ${WIDTH}x$HEIGHT rot 0"), summary)
        assertTrue(summary.contains("$DENSITY dpi"), summary)
        assertTrue(summary.contains("source: uiautomator accessibility dump of the active window"), summary)
        assertFalse(summary.contains('\n'), "one line: $summary")
    }

    @Test
    fun `the summary says what it does not know rather than leaving it out`() {
        val summary = observe(root = null, density = null, metrics = DisplayMetrics.UNKNOWN).summary()

        assertTrue(summary.contains("window unknown"), summary)
        assertTrue(summary.contains("viewport unknown"), summary)
        assertTrue(summary.contains("density unknown"), summary)
    }

    @Test
    fun `a viewport away from the corner gives its origin`() {
        val dialog = UiNode.Bounds(100, 600, 980, 1700)

        assertEquals("880x1100 at (100,600) rot 0", observe(root = window(bounds = dialog)).describeViewport())
    }

    @Test
    fun `the limits note is one line naming each limit`() {
        val note = observe(tags = TestTagSupport.UNAVAILABLE, density = null).limitsNote()

        assertTrue(note.startsWith("Limits: "), note)
        assertFalse(note.contains('\n'), note)
        listOf(
            ObservationLimit.ACCESSIBILITY_TREE_ONLY,
            ObservationLimit.NO_OCCLUSION,
            ObservationLimit.TEST_TAGS_NOT_OBSERVED,
            ObservationLimit.DENSITY_UNKNOWN,
        ).forEach { assertTrue(note.contains(it.description), "$it missing from: $note") }
    }

    @Suppress("LongParameterList")
    private fun observe(
        root: UiNode? = window(),
        framework: UiFramework = UiFramework.COMPOSE,
        tags: TestTagSupport = TestTagSupport.AVAILABLE,
        density: Int? = DENSITY,
        rotation: Int? = 0,
        metrics: DisplayMetrics = DisplayMetrics(density, WIDTH, HEIGHT),
        started: Long = STARTED,
        completed: Long = STARTED + 500,
    ): UiObservation {
        // Composed the way UiTreeOperations.observe composes it: density lives on the tree.
        val tree = UiTree(root, framework, tags, densityDpi = density, rotation = rotation)
        return UiObservation(tree, SERIAL, started, completed, metrics)
    }

    private fun window(pkg: String = PACKAGE, bounds: UiNode.Bounds = FULL_SCREEN) = UiNode(
        className = "android.widget.FrameLayout",
        packageName = pkg,
        text = "",
        contentDescription = "",
        resourceId = "",
        bounds = bounds,
        clickable = false,
        longClickable = false,
        enabled = true,
        focused = false,
        focusable = false,
        scrollable = false,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = emptyList(),
    )

    private companion object {
        const val SERIAL = "emulator-5554"
        const val PACKAGE = "spock.adb.sample"
        const val WIDTH = 1080
        const val HEIGHT = 2400
        const val DENSITY = 420

        /** 2026-09-23T10:15:02Z. */
        const val STARTED = 1_790_158_502_000L

        val FULL_SCREEN = UiNode.Bounds(0, 0, WIDTH, HEIGHT)
    }
}
