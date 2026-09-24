package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/** The words on the Inspector's header line: the badge, the one-line capture, the tag state. */
class InspectorHeaderTextTest {

    private val tree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
    ).copy(densityDpi = 420)

    @Test
    fun `the badge is one word per framework`() {
        assertEquals("Compose", frameworkBadge(UiFramework.COMPOSE))
        assertEquals("Hybrid", frameworkBadge(UiFramework.HYBRID))
        assertEquals("Views", frameworkBadge(UiFramework.VIEWS))
        assertEquals("Unknown", frameworkBadge(UiFramework.UNKNOWN))
    }

    @Test
    fun `the capture reads as device, window, time, viewport and density`() {
        val observation = UiObservation(
            tree,
            deviceSerial = "emulator-5554",
            startedAtMillis = STARTED,
            completedAtMillis = STARTED + 900,
            metrics = DisplayMetrics(420, 1080, 2220),
        )

        assertEquals(
            "Pixel 7 · com.example.compose · 10:15:02 · 1080x2220 rot 0 · 420 dpi",
            captureSummary(observation, "Pixel 7", ZoneOffset.UTC),
        )
    }

    @Test
    fun `what the capture could not read is named, not left out`() {
        val observation = UiObservation(
            tree.copy(root = null, densityDpi = null),
            deviceSerial = "emulator-5554",
            startedAtMillis = STARTED,
            completedAtMillis = STARTED,
            metrics = DisplayMetrics.UNKNOWN,
        )

        assertEquals(
            "Pixel 7 · window unknown · 10:15:02 · viewport unknown · density unknown",
            captureSummary(observation, "Pixel 7", ZoneOffset.UTC),
        )
    }

    @Test
    fun `tag exposure is stated only where Compose tags are a question`() {
        assertEquals("Test tags exposed", tagExposure(tree.copy(testTagSupport = UiTree.TestTagSupport.AVAILABLE)))
        assertEquals(
            "No test tags exposed",
            tagExposure(tree.copy(testTagSupport = UiTree.TestTagSupport.UNAVAILABLE)),
        )
        assertNull(tagExposure(tree.copy(testTagSupport = UiTree.TestTagSupport.NOT_APPLICABLE)))
    }

    private companion object {
        /** 2026-09-23T10:15:02Z. */
        const val STARTED = 1_790_158_502_000L
    }
}
