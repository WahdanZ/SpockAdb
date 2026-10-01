package spock.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Home tab's one line about permissions. */
class PermissionSummaryTest {

    @Test
    fun `permissions the system will not ask for again are counted`() {
        assertEquals("3 granted / 2 denied (1 won't ask again)", PermissionSummary(3, 2, 1).describe())
    }

    @Test
    fun `with none of those the line is as it was`() {
        assertEquals("3 granted / 2 denied", PermissionSummary(3, 2).describe())
    }

    @Test
    fun `an app with no runtime permissions says so`() {
        assertEquals("No runtime permissions", PermissionSummary(0, 0).describe())
    }
}
