package spock.adb.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spock.adb.models.ActivityData
import spock.adb.models.FragmentData

class AppBackStackRowTest {

    private val app = "com.example.app"

    @Test
    fun `numbers activities from the top and nests fragments under their activity`() {
        val stack = listOf(
            ActivityData(
                "$app.DetailActivity",
                listOf(FragmentData("DetailFragment", mutableListOf(FragmentData("ChildFragment")))),
                "Resumed",
            ),
            ActivityData("$app.MainActivity", status = "Stopped"),
        )

        assertEquals(
            listOf(
                AppBackStackRow.Activity("$app.DetailActivity", app, 1, "Resumed"),
                AppBackStackRow.Fragment("DetailFragment", 1),
                AppBackStackRow.Fragment("ChildFragment", 2),
                AppBackStackRow.Activity("$app.MainActivity", app, 2, "Stopped"),
            ),
            stack.toAppBackStackRows(app),
        )
    }

    @Test
    fun `shortens activities in the app's package and keeps others whole`() {
        assertEquals("ui.Home", AppBackStackRow.Activity("$app.ui.Home", app, 1, "").displayText())
        assertEquals(
            "com.other.Login",
            AppBackStackRow.Activity("com.other.Login", app, 1, "").displayText(),
        )
    }

    @Test
    fun `badges an activity's state and nothing on a fragment`() {
        assertEquals("RESUMED", AppBackStackRow.Activity("$app.A", app, 1, "Resumed").badgeText())
        assertNull(AppBackStackRow.Activity("$app.A", app, 1, "").badgeText())
        assertNull(AppBackStackRow.Fragment("HomeFragment", 1).badgeText())
    }
}
