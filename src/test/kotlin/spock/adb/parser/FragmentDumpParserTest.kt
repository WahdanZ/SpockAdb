package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixtures use `trimMargin` rather than `trimIndent`: these parsers key off the exact
 * leading whitespace that `dumpsys` emits, which `trimIndent` would strip.
 */
class FragmentDumpParserTest {

    @Test
    fun `keeps a fragment that reports itself visible with a parent`() {
        val dump = """
            |  TASK com.example.app id=42
            |    ACTIVITY com.example.app/.MainActivity abc pid=1234
            |      Added Fragments:
            |        #0: HomeFragment{aaa111}
            |      FragmentManager misc state:
            |      HomeFragment{aaa111}
            |        mUserVisibleHint=true
            |      mParent=HomeFragment{aaa111}
        """.trimMargin()

        assertEquals(listOf("HomeFragment"), FragmentDumpParser.parse(dump).map { it.fragment })
    }

    @Test
    fun `drops a fragment that reports itself not visible`() {
        val dump = """
            |  TASK com.example.app id=42
            |      Added Fragments:
            |        #0: HiddenFragment{bbb222}
            |      FragmentManager misc state:
            |      HiddenFragment{bbb222}
            |        mUserVisibleHint=false
            |      mParent=HiddenFragment{bbb222}
        """.trimMargin()

        assertTrue(FragmentDumpParser.parse(dump).isEmpty())
    }

    @Test
    fun `drops fragments whose parent is null`() {
        val dump = """
            |  TASK com.example.app id=42
            |      Added Fragments:
            |        #0: DetachedFragment{ccc333}
            |      FragmentManager misc state:
            |      DetachedFragment{ccc333}
            |        mUserVisibleHint=true
            |        {parent=null}
            |      mParent=DetachedFragment{ccc333}
        """.trimMargin()

        assertTrue(FragmentDumpParser.parse(dump).isEmpty())
    }

    @Test
    fun `reads the trailing Added Fragments block when NavHostFragment is present`() {
        // With the Navigation component the destination fragments are listed in the block
        // that follows the NavHostFragment's own, so the parser takes the last section.
        val dump = """
            |  TASK com.example.app id=42
            |      Added Fragments:
            |        #0: NavHostFragment{aaa}
            |      Added Fragments:
            |        #0: DashboardFragment{bbb111}
            |        #1: ProfileFragment{ccc222}
            |        #2: BackStackEntry{ddd333}
        """.trimMargin()

        assertEquals(
            listOf("DashboardFragment", "ProfileFragment"),
            FragmentDumpParser.parse(dump).map { it.fragment },
        )
    }

    @Test
    fun `returns empty list when the dump contains no task section`() {
        assertTrue(FragmentDumpParser.parse("").isEmpty())
        assertTrue(FragmentDumpParser.parse("no task here").isEmpty())
    }
}
