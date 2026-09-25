package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DisplayMetricsTest {

    @Test
    fun `an override size wins over the physical size, as it does for density`() {
        // `wm size 720x1600` is what apps are laid out against, so it is the display they see.
        assertEquals(720 to 1600, DisplayMetricsReader.parseSize("Physical size: 1080x2400\nOverride size: 720x1600\n"))
        assertEquals(1080 to 2400, DisplayMetricsReader.parseSize("Physical size: 1080x2400\n"))
    }

    @Test
    fun `an answer that is not a size is unknown, never a guessed default`() {
        assertNull(DisplayMetricsReader.parseSize(""))
        assertNull(DisplayMetricsReader.parseSize("Permission denied"))
        assertNull(DisplayMetricsReader.parseSize("Physical size: 0x2400"))
        assertNull(DisplayMetricsReader.parseSize("Physical size: 1080x"))
        assertNull(DisplayMetricsReader.parseSize("Physical size: 99999999999x2400"))
    }
}
