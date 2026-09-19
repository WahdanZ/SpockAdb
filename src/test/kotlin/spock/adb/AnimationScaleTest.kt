package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What the animation-scale dropdowns show, and what a device's answer means.
 *
 * `settings get` answers with whatever is stored — "1", "1.0", or the literal "null" where the
 * setting has never been written — so the raw string rarely equals the dropdown entry it means.
 * Comparing them as text selected nothing on a device that answered "1", and the old fallback
 * turned an unreadable answer into "0.0", which the dropdown showed as Off: a device with
 * animations running, displayed as a device with them switched off.
 */
class AnimationScaleTest {

    @Test
    fun `a value is shown as the multiplier the system settings screen shows`() {
        assertEquals("Off", scaleText("0.0"))
        assertEquals("0.5×", scaleText("0.5"))
        assertEquals("1×", scaleText("1.0"))
        assertEquals("1.5×", scaleText("1.5"))
        assertEquals("10×", scaleText("10.0"))
        assertEquals("", scaleText(null), "an unreadable value is not a scale of any size")
    }

    @Test
    fun `a device is matched on the number it answered, not on its spelling`() {
        assertEquals("1.0", animationScaleEntry("1", SCALES))
        assertEquals("1.0", animationScaleEntry("1.0", SCALES))
        assertEquals("1.0", animationScaleEntry("  1.00 \n", SCALES))
        assertEquals("0.0", animationScaleEntry("0", SCALES))
        assertEquals("0.5", animationScaleEntry(".5", SCALES))
    }

    @Test
    fun `an answer that is not a scale selects nothing rather than guessing Off`() {
        assertNull(animationScaleEntry("null", SCALES))
        assertNull(animationScaleEntry("", SCALES))
        assertNull(animationScaleEntry(null, SCALES))
        assertNull(animationScaleEntry("3.0", SCALES), "a scale the dropdown does not offer is not one of them")
    }

    @Test
    fun `every entry answers for itself`() {
        SCALES.forEach { entry ->
            assertEquals(entry, animationScaleEntry(entry, SCALES))
            assertEquals(DEFAULT_SCALE, animationScaleEntry(DEFAULT_SCALE, SCALES))
        }
    }
}
