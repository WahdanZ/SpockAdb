package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the Inspector's status line says when a capture fails: words for a person, not an agent. */
class CaptureFailureTextTest {

    @Test
    fun `a lost device tells a person what they can do, not which tool to call`() {
        val lost = UiCaptureException(
            UiCaptureException.Kind.DEVICE_UNAVAILABLE,
            "Device emulator-5554 is no longer available (device offline).",
        )

        val text = captureFailureText(lost)

        assertTrue(text.contains("emulator-5554"), text)
        assertTrue(text.contains("Reconnect"), text)
        assertTrue(text.contains("Devices tab"), text)
        assertFalse(text.contains("android_list_devices"), text)
    }

    @Test
    fun `other failures are shown as they are`() {
        val refused = UiCaptureException(UiCaptureException.Kind.DUMP_REFUSED, "uiautomator could not dump the UI.")

        assertEquals("Capture failed: uiautomator could not dump the UI.", captureFailureText(refused))
    }
}
