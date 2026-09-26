package spock.adb.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How Home names the fragments on screen, in the width of a docked tool window. */
class ScreenTextTest {

    @Test
    fun `no fragments, a dash`() {
        assertEquals("—", ScreenText.fragments(emptyList()))
    }

    @Test
    fun `one fragment, by its simple name`() {
        assertEquals("CartFragment", ScreenText.fragments(listOf("spock.adb.sample.CartFragment")))
    }

    @Test
    fun `several, the first and how many more`() {
        assertEquals("HomeFragment +2", ScreenText.fragments(listOf("a.HomeFragment", "a.ListFragment", "a.Detail")))
    }
}
