package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Inspector's status line, for each state it can be in. */
class InspectorStatusTest {

    private val tree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
    )
    private val pixel = DeviceLabel("emulator-5554", "Google sdk_gphone64_arm64")
    private val tablet = DeviceLabel("R58M123", "Samsung Galaxy Tab")

    @Test
    fun `a capture keeps its summary while the same device is reselected`() {
        // The device list refreshes on its own and re-selects the same device each time; that
        // used to replace the node count with "press Capture UI" under a captured tree.
        val text = InspectorStatus.text(pixel, capturedFrom = pixel, tree = tree, capturing = false, failure = null)

        assertEquals("8 nodes · 3 interactive", text)
    }

    @Test
    fun `another device marks the capture stale instead of replacing it`() {
        val text = InspectorStatus.text(tablet, capturedFrom = pixel, tree = tree, capturing = false, failure = null)

        assertEquals("Captured from Google sdk_gphone64_arm64 — device changed; capture again.", text)
    }

    @Test
    fun `no device at all is stale too, and says what to do`() {
        val text = InspectorStatus.text(null, capturedFrom = pixel, tree = tree, capturing = false, failure = null)

        assertTrue(text.startsWith("Captured from Google sdk_gphone64_arm64 — no device selected now"), text)
    }

    @Test
    fun `a failed capture reads as the failure text`() {
        val failure = UiCaptureException(UiCaptureException.Kind.DUMP_REFUSED, "uiautomator could not dump the UI.")

        val text = InspectorStatus.text(pixel, capturedFrom = pixel, tree = tree, capturing = false, failure = failure)

        assertEquals(captureFailureText(failure), text)
    }

    @Test
    fun `before any capture it names the device, or asks for one`() {
        assertEquals(
            "Ready to capture Google sdk_gphone64_arm64.",
            InspectorStatus.text(pixel, capturedFrom = null, tree = null, capturing = false, failure = null),
        )
        assertEquals(
            InspectorStatus.NO_DEVICE,
            InspectorStatus.text(null, capturedFrom = null, tree = null, capturing = false, failure = null),
        )
    }

    @Test
    fun `a capture in progress says so over everything else`() {
        val text = InspectorStatus.text(pixel, capturedFrom = tablet, tree = tree, capturing = true, failure = null)

        assertEquals("Capturing Google sdk_gphone64_arm64…", text)
    }
}
