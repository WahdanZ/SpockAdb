package spock.adb.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the Spock Actions popup lists in each section. */
class PopupSectionsTest {

    private val all = listOf("restart", "stop", "clear", "diagnose")

    @Test
    fun `pins that no longer exist are dropped`() {
        val sections = PopupSections.of(pinned = listOf("gone", "stop"), recent = emptyList(), all = all)
        assertEquals(listOf("stop"), sections.pinned)
    }

    @Test
    fun `a recent action already pinned is not listed twice`() {
        val sections = PopupSections.of(pinned = listOf("stop"), recent = listOf("stop", "clear"), all = all)
        assertEquals(listOf("clear"), sections.recent)
    }

    @Test
    fun `every action is still under All`() {
        val sections = PopupSections.of(pinned = listOf("stop"), recent = listOf("clear"), all = all)
        assertEquals(all, sections.all)
    }
}
