package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DisplayDensityTest {
    @Test
    fun `override density takes precedence over physical density`() {
        assertEquals(320, DisplayDensity.parse("Physical density: 480\nOverride density: 320\n"))
        assertEquals(480, DisplayDensity.parse("Physical density: 480\n"))
    }

    @Test
    fun `unavailable or invalid density never becomes a guessed default`() {
        assertNull(DisplayDensity.parse("Permission denied"))
        assertNull(DisplayDensity.parse("Physical density: 0"))
        assertNull(DisplayDensity.parse("Physical density: 9999999999999"))
    }
}
