package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackStackParserTest {

    @Test
    fun `groups history entries by package preserving stack order`() {
        val dump = """
            * Hist #2: ActivityRecord{aaa u0 com.example.app/.DetailActivity t10}
            * Hist #1: ActivityRecord{bbb u0 com.example.app/.ListActivity t10}
            * Hist #0: ActivityRecord{ccc u0 com.android.launcher3/.Launcher t1}
        """.trimIndent()

        val result = BackStackParser.parseHistory(dump)

        assertEquals(2, result.size)
        val app = result.first { it.appPackage == "com.example.app" }
        assertEquals(
            listOf("com.example.app.DetailActivity", "com.example.app.ListActivity"),
            app.activitiesList,
        )
    }

    @Test
    fun `qualifies package-relative activity names against their own package`() {
        val dump = "* Hist #0: ActivityRecord{aaa u0 com.example.app/.MainActivity t1}"

        val result = BackStackParser.parseHistory(dump)

        assertEquals(listOf("com.example.app.MainActivity"), result.single().activitiesList)
    }

    @Test
    fun `does not attribute an activity to the previously seen package`() {
        // The original grouping relied on a mutable `appPackage` shared between the key
        // selector and the value transform, which is only correct because `groupBy` happens
        // to call them back-to-back per element. This pins the behaviour independently of
        // that implementation detail.
        val dump = """
            * Hist #1: ActivityRecord{aaa u0 com.first.app/.FirstActivity t1}
            * Hist #0: ActivityRecord{bbb u0 com.second.app/.SecondActivity t2}
        """.trimIndent()

        val result = BackStackParser.parseHistory(dump)

        assertEquals(
            listOf("com.first.app.FirstActivity"),
            result.first { it.appPackage == "com.first.app" }.activitiesList,
        )
        assertEquals(
            listOf("com.second.app.SecondActivity"),
            result.first { it.appPackage == "com.second.app" }.activitiesList,
        )
    }

    @Test
    fun `ignores lines that are not history entries`() {
        val dump = """
            Display #0 (activities from top to bottom):
              Stack #1:
            * Hist #0: ActivityRecord{aaa u0 com.example.app/.MainActivity t1}
              mResumedActivity: ActivityRecord{aaa u0 com.example.app/.MainActivity t1}
        """.trimIndent()

        assertEquals(1, BackStackParser.parseHistory(dump).size)
    }

    @Test
    fun `returns empty for empty output`() {
        assertTrue(BackStackParser.parseHistory("").isEmpty())
        assertTrue(BackStackParser.parseLegacy("").isEmpty())
    }

    @Test
    fun `keeps digits and underscores in package and activity names`() {
        // `[a-zA-Z.]` stopped at the first digit, so this package parsed as `com.android.launcher`
        // and its activity as the class `com.android.launcher.Launcher`, which does not exist.
        val dump = "* Hist #0: ActivityRecord{aaa u0 com.android.launcher3/.Launcher2 t1}"

        val result = BackStackParser.parseHistory(dump).single()

        assertEquals("com.android.launcher3", result.appPackage)
        assertEquals(listOf("com.android.launcher3.Launcher2"), result.activitiesList)
    }

    @Test
    fun `drops history lines whose activity could not be read`() {
        // These used to survive as an empty string and render in the popup as the bare text `0-`.
        val dump = """
            * Hist #1: ActivityRecord{aaa u0 com.example.app/.MainActivity t1}
            * Hist #0: ActivityRecord{bbb u0 com.example.app t1}
        """.trimIndent()

        val app = BackStackParser.parseHistory(dump).single()

        assertEquals(listOf("com.example.app.MainActivity"), app.activitiesList)
    }

    @Test
    fun `keeps a task whose activities were all unreadable`() {
        val dump = "* Hist #0: ActivityRecord{aaa u0 com.example.app t1}"

        val app = BackStackParser.parseHistory(dump).single()

        assertEquals("com.example.app", app.appPackage)
        assertTrue(app.activitiesList.isEmpty())
    }

    @Test
    fun `marks the task holding the resumed activity as the foreground one`() {
        val dump = """
            * Hist #0: ActivityRecord{aaa u0 com.android.launcher3/.Launcher t1}
            * Hist #0: ActivityRecord{bbb u0 com.example.app/.MainActivity t2}
              mResumedActivity: ActivityRecord{bbb u0 com.example.app/.MainActivity t2}
        """.trimIndent()

        val result = BackStackParser.parseHistory(dump)

        assertFalse(result.first { it.appPackage == "com.android.launcher3" }.isForeground)
        assertTrue(result.first { it.appPackage == "com.example.app" }.isForeground)
    }

    @Test
    fun `falls back to the top of the stack when no resumed activity is reported`() {
        // Older dumps, and devices that report `mResumedActivity: null`, give nothing to match.
        val dump = """
            * Hist #0: ActivityRecord{aaa u0 com.example.app/.MainActivity t2}
            * Hist #0: ActivityRecord{bbb u0 com.android.launcher3/.Launcher t1}
              mResumedActivity: null
        """.trimIndent()

        val result = BackStackParser.parseHistory(dump)

        assertTrue(result.first().isForeground)
        assertFalse(result.last().isForeground)
    }

    @Test
    fun `legacy parser reads ActivityRecord entries from running activities section`() {
        val dump = """
            Running activities (most recent first):
              TaskRecord{123 #10 A=com.example.app U=0 sz=2}
                Run #1: ActivityRecord{aaa u0 com.example.app/.DetailActivity t10}
                Run #0: ActivityRecord{bbb u0 com.example.app/.ListActivity t10}
        """.trimIndent()

        val result = BackStackParser.parseLegacy(dump)

        assertEquals(1, result.size)
        assertEquals(
            listOf("com.example.app.DetailActivity", "com.example.app.ListActivity"),
            result.single().activitiesList,
        )
    }
}
