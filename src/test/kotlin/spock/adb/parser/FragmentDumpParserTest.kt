package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixtures use `trimMargin` rather than `trimIndent`: these parsers key off the exact
 * leading whitespace that `dumpsys` emits, which `trimIndent` would strip.
 */
class FragmentDumpParserTest {

    /**
     * Trimmed from `dumpsys activity <package>` on an API 34 emulator, in the order the device
     * emits it: the activity's own ReportFragment first, then the child FragmentManager holding
     * what is on screen, then the NavHostFragment that hosts it.
     *
     * `dumpsys activity top` reports no fragment state at all on Android 13 and later, which is
     * what made Current fragment answer "no fragments" for every app that had them.
     */
    private val packageScopedNavHostDump = """
          TASK com.example.app id=222 userId=0
            ACTIVITY com.example.app/.MainActivity 14c276b pid=3189
              Local Activity 86f23ad State:
                Added Fragments:
                  #0: ReportFragment{8ff16e2 #0 androidx.lifecycle.LifecycleDispatcher.report_fragment_tag}
                FragmentManager misc state:
                  mHost=android.app.Activity${'$'}HostCallbacks@261ca73
              Child FragmentManager{f8b2c1a in NavHostFragment{446e074}}:
                Added Fragments:
                  #0: NotificationsFragment{bfbf202} (6f7931fe id=0x7f080152 tag=4a789f5f)
                Back Stack:
                  #0: BackStackEntry{28232aa 4a789f5f}
              FragmentManager{2c9a1de in MainActivity{14c276b}}:
                Added Fragments:
                  #0: NavHostFragment{446e074} (a59d3bb2 id=0x7f080152)
                Back Stack Index: 0
    """.trimIndent()

    @Test
    fun `reads the fragment on screen from a package-scoped dump`() {
        val fragments = FragmentDumpParser.parse(packageScopedNavHostDump)

        assertEquals(
            listOf("NotificationsFragment"),
            fragments.map { it.fragment },
            "the host's own ReportFragment and NavHostFragment are not what is on screen",
        )
    }

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
