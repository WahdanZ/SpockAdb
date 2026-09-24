package spock.adb.uitree

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * One capture of the screen, and what it can and cannot tell.
 *
 * A tree alone said nothing about which device it came from, which window, when, at what size
 * or density, or what `uiautomator` leaves out — so the Inspector and every MCP tool described
 * a capture differently, or not at all, and a reader could not tell a screen that has no
 * keyboard from a capture that cannot see one. This is the one description they all give.
 *
 * Density is carried by [tree], because the audit reads it there; it is set once, when the
 * capture is taken, and [densityDpi] reads it back. [metrics] is kept for the display size.
 */
data class UiObservation(
    val tree: UiTree,
    val deviceSerial: String,
    /** Host clock, not the device's: the two can disagree by minutes. */
    val startedAtMillis: Long,
    val completedAtMillis: Long,
    val metrics: DisplayMetrics,
    val source: DataSource = DataSource.UIAUTOMATOR_ACTIVE_WINDOW,
) {

    val densityDpi: Int? get() = tree.densityDpi

    /**
     * The package that owns the dumped window. Not "the foreground app": with a system dialog
     * or another app's window on top, it is that window's package, and the app under it is not
     * in the dump at all.
     */
    val windowPackage: String? get() = tree.root?.packageName?.takeIf { it.isNotBlank() }

    /**
     * The display in the orientation the dump was taken in. `wm size` reports the natural
     * orientation, so a quarter turn swaps width and height. Null when either is unknown.
     */
    val displayRect: UiNode.Bounds?
        get() {
            val width = metrics.naturalWidthPx ?: return null
            val height = metrics.naturalHeightPx ?: return null
            return when (tree.rotation) {
                ROTATION_0, ROTATION_180 -> UiNode.Bounds(0, 0, width, height)
                ROTATION_90, ROTATION_270 -> UiNode.Bounds(0, 0, height, width)
                else -> null
            }
        }

    /** The dumped window's own bounds, when it has any area. A dialog's is smaller than the display. */
    val windowRect: UiNode.Bounds? get() = tree.root?.bounds?.takeIf { it.hasArea() }

    /**
     * What of the screen this capture covers: the window clipped to the display when both are
     * known, else whichever one is. Null when neither is, or when they do not overlap.
     */
    val viewport: UiNode.Bounds?
        get() {
            val display = displayRect
            val window = windowRect
            return when {
                display != null && window != null -> display.intersect(window)
                else -> display ?: window
            }
        }

    val limits: List<ObservationLimit>
        get() = buildList {
            add(ObservationLimit.ACCESSIBILITY_TREE_ONLY)
            add(ObservationLimit.NO_OCCLUSION)
            if (tree.testTagSupport == UiTree.TestTagSupport.UNAVAILABLE) add(ObservationLimit.TEST_TAGS_NOT_OBSERVED)
            if (tree.framework.hasCompose() && tree.testTagSupport == UiTree.TestTagSupport.AVAILABLE) {
                add(ObservationLimit.INTEROP_TAG_PROVENANCE)
            }
            if (densityDpi == null) add(ObservationLimit.DENSITY_UNKNOWN)
            when {
                viewport == null -> add(ObservationLimit.VIEWPORT_UNKNOWN)
                displayRect == null -> add(ObservationLimit.DISPLAY_SIZE_UNKNOWN)
            }
        }

    /**
     * One line: device, window, when, viewport, density and source, e.g. "Observed on
     * emulator-5554, window com.example, 2026-09-23T10:15:02Z (host clock, 1.8 s capture),
     * viewport 1080x2400 rot 0, 420 dpi, source: uiautomator accessibility dump of the active window."
     */
    fun summary(): String = buildString {
        append("Observed on ").append(deviceSerial)
        append(", window ").append(windowPackage ?: "unknown")
        append(", ").append(Instant.ofEpochMilli(startedAtMillis).truncatedTo(ChronoUnit.SECONDS))
        append(" (host clock, ").append(captureSeconds()).append(" s capture)")
        append(", viewport ").append(describeViewport())
        append(", ").append(describeDensity())
        append(", source: ").append(source.description).append('.')
    }

    /** One line naming every limit that applies to this capture. */
    fun limitsNote(): String = "Limits: " + limits.joinToString("; ") { it.description } + "."

    /** `1080x2400 rot 0`, with the origin when the viewport does not start at the corner. */
    fun describeViewport(): String {
        val rect = viewport ?: return "unknown"
        return buildString {
            append(rect.width).append('x').append(rect.height)
            if (rect.left != 0 || rect.top != 0) append(" at (${rect.left},${rect.top})")
            append(" rot ").append(tree.rotation ?: "unknown")
        }
    }

    fun describeDensity(): String = densityDpi?.let { "$it dpi" } ?: "density unknown"

    private fun captureSeconds(): String =
        String.format(Locale.ROOT, "%.1f", (completedAtMillis - startedAtMillis).coerceAtLeast(0) / MILLIS_PER_SECOND)

    /** Where the observation came from. One source today; the name leaves room for another. */
    enum class DataSource(val description: String) {
        UIAUTOMATOR_ACTIVE_WINDOW("uiautomator accessibility dump of the active window"),
    }

    private companion object {
        const val ROTATION_0 = 0
        const val ROTATION_90 = 1
        const val ROTATION_180 = 2
        const val ROTATION_270 = 3
        const val MILLIS_PER_SECOND = 1_000.0

        fun UiNode.Bounds.hasArea() = width > 0 && height > 0

        fun UiNode.Bounds.intersect(other: UiNode.Bounds): UiNode.Bounds? = UiNode.Bounds(
            maxOf(left, other.left),
            maxOf(top, other.top),
            minOf(right, other.right),
            minOf(bottom, other.bottom),
        ).takeIf { it.hasArea() }

        fun UiFramework.hasCompose() = this == UiFramework.COMPOSE || this == UiFramework.HYBRID
    }
}

/** What a capture cannot show. Each is disclosed rather than left for the reader to assume. */
enum class ObservationLimit(val description: String) {
    ACCESSIBILITY_TREE_ONLY(
        "the active window's accessibility tree only, not every composable, and not other " +
            "windows such as the keyboard, other apps' dialogs or the system bars",
    ),
    NO_OCCLUSION("bounds cannot show whether an element is covered by another"),
    TEST_TAGS_NOT_OBSERVED("no exposed Compose test tags were observed"),
    INTEROP_TAG_PROVENANCE(
        "a View id inside an AndroidView counts as an exposed tag, so tags may look exposed when " +
            "Compose's are not",
    ),
    DENSITY_UNKNOWN("display density could not be read, so no dp sizes"),
    DISPLAY_SIZE_UNKNOWN("display size or rotation could not be read, so the viewport is the window's own bounds"),
    VIEWPORT_UNKNOWN("no viewport: neither the display size nor the window bounds are known"),
}
