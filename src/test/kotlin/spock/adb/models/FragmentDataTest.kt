package spock.adb.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FragmentDataTest {

    @Test
    fun `lists each parent before its children, with how deep each one sits`() {
        val detail = FragmentData(
            "DetailFragment",
            mutableListOf(FragmentData("ChildFragment", mutableListOf(FragmentData("GrandchildFragment")))),
        )

        assertEquals(
            listOf(
                FragmentRow("DetailFragment", 0),
                FragmentRow("ChildFragment", 1),
                FragmentRow("GrandchildFragment", 2),
            ),
            detail.flatten(),
        )
    }

    @Test
    fun `keeps the class name free of the indentation, so it can be looked up`() {
        val rows = FragmentData("A", mutableListOf(FragmentData("B", mutableListOf(FragmentData("C"))))).flatten()

        assertEquals(listOf("A", "B", "C"), rows.map { it.fragment })
    }
}
