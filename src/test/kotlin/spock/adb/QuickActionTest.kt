package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle

/**
 * The rules behind Quick actions: what a stored pin list means, where a dragged button lands,
 * and what a typed search matches.
 *
 * All three are decided here rather than in the Swing code, because none of them can be seen
 * from a screenshot — a drop index that is off by one looks exactly like one that is right
 * until the button lands on the wrong side of its neighbour.
 */
class QuickActionTest {

    private val row = listOf(
        QuickAction.RESTART_APP,
        QuickAction.FORCE_STOP,
        QuickAction.CLEAR_DATA,
    )

    @Test
    fun `stored names that no longer exist cost nobody the rest of their pins`() {
        val read = QuickAction.read(listOf("FORCE_STOP", "AN_ACTION_FROM_A_LATER_VERSION", "RESTART_APP"))

        assertEquals(listOf(QuickAction.FORCE_STOP, QuickAction.RESTART_APP), read)
    }

    @Test
    fun `the stored order is the order shown, and an action is pinned once`() {
        assertEquals(
            listOf(QuickAction.CLEAR_DATA, QuickAction.RESTART_APP),
            QuickAction.read(listOf("CLEAR_DATA", "RESTART_APP", "CLEAR_DATA")),
        )
    }

    @Test
    fun `moving right accounts for the gap the action leaves behind`() {
        // "Move right" on the first action puts it after the second, not back where it was.
        assertEquals(
            listOf(QuickAction.FORCE_STOP, QuickAction.RESTART_APP, QuickAction.CLEAR_DATA),
            movedTo(row, from = 0, to = 2),
        )
        assertEquals(
            listOf(QuickAction.FORCE_STOP, QuickAction.CLEAR_DATA, QuickAction.RESTART_APP),
            movedTo(row, from = 0, to = 3),
            "dropping past the end puts it last",
        )
    }

    @Test
    fun `moving left inserts before the target`() {
        assertEquals(
            listOf(QuickAction.RESTART_APP, QuickAction.CLEAR_DATA, QuickAction.FORCE_STOP),
            movedTo(row, from = 2, to = 1),
        )
        assertEquals(
            listOf(QuickAction.CLEAR_DATA, QuickAction.RESTART_APP, QuickAction.FORCE_STOP),
            movedTo(row, from = 2, to = 0),
        )
    }

    @Test
    fun `a move that goes nowhere changes nothing`() {
        assertEquals(row, movedTo(row, from = 1, to = 1))
        assertEquals(row, movedTo(row, from = 1, to = 2))
        assertEquals(row, movedTo(row, from = 7, to = 0), "an index that is not in the row is not a move")
    }

    @Test
    fun `a button is dropped before the one whose middle it has passed`() {
        val bounds = listOf(
            Rectangle(0, 0, 100, 30),
            Rectangle(100, 0, 100, 30),
            Rectangle(200, 0, 100, 30),
        )

        assertEquals(0, dropIndexAt(bounds, Point(10, 10)))
        assertEquals(1, dropIndexAt(bounds, Point(60, 10)), "past the first middle is after the first")
        assertEquals(2, dropIndexAt(bounds, Point(160, 10)))
        assertEquals(3, dropIndexAt(bounds, Point(290, 10)), "past the last middle is the end")
        assertEquals(0, dropIndexAt(emptyList(), Point(10, 10)))
    }

    @Test
    fun `a wrapped row drops onto the line the pointer is on`() {
        val bounds = listOf(
            Rectangle(0, 0, 100, 30),
            Rectangle(100, 0, 100, 30),
            Rectangle(0, 30, 100, 30),
        )

        assertEquals(2, dropIndexAt(bounds, Point(10, 40)), "the second line holds only the third button")
        assertEquals(3, dropIndexAt(bounds, Point(90, 40)))
        // Below every line: the whole row is considered rather than refusing to be anywhere.
        assertEquals(3, dropIndexAt(bounds, Point(290, 90)))
    }

    @Test
    fun `every word has to match, in any order`() {
        val label = "Clear data and restart…"
        val tooltip = "Delete all app data, then relaunch the app"

        assertTrue(matchesActionSearch("clear data", label, tooltip))
        assertTrue(matchesActionSearch("data clear", label, tooltip), "word order is not part of the search")
        assertTrue(matchesActionSearch("RELAUNCH", label, tooltip), "the tooltip is searched too")
        assertFalse(matchesActionSearch("clear cache", label, tooltip))
        assertTrue(matchesActionSearch("   ", label, tooltip), "an empty search hides nothing")
        assertTrue(matchesActionSearch("restart", label, null), "a control with no tooltip still matches")
    }
}
