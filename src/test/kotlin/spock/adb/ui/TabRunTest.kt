package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which tabs fit on the row, and where the selected one goes when it would not. */
class TabRunTest {

    private val widths = listOf(50, 50, 50, 50, 50)

    @Test
    fun `everything fits, in the order it was added`() {
        assertEquals(listOf(0, 1, 2, 3, 4), tabRun(widths, selected = 3, room = 1000, gap = 0))
    }

    @Test
    fun `what does not fit is cut from the end`() {
        assertEquals(listOf(0, 1, 2), tabRun(widths, selected = 0, room = 160, gap = 0))
    }

    @Test
    fun `a selected tab that would overflow replaces the last one that fit`() {
        assertEquals(listOf(0, 1, 4), tabRun(widths, selected = 4, room = 160, gap = 0))
    }

    @Test
    fun `a wide selected tab displaces as many as it needs`() {
        val wide = listOf(50, 50, 50, 120)
        assertEquals(listOf(0, 3), tabRun(wide, selected = 3, room = 170, gap = 0))
    }

    @Test
    fun `gaps count against the room`() {
        assertEquals(listOf(0, 1), tabRun(widths, selected = -1, room = 150, gap = 10))
    }

    @Test
    fun `a selected tab wider than the row is still shown`() {
        assertEquals(listOf(2), tabRun(listOf(50, 50, 400), selected = 2, room = 100, gap = 0))
    }
}
