package spock.adb.parser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.models.FragmentData

/**
 * Fixtures use `trimMargin` rather than `trimIndent` where it matters: the parser walks the
 * dump by the exact leading whitespace that `dumpsys` emits.
 */
class FragmentDumpParserTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/dumpsys/$name")) { "missing fixture $name" }.readText()

    private fun List<FragmentData>.render(): List<String> = flatMap { fragment ->
        listOf(fragment.fragment) + fragment.innerFragments.render().map { "  $it" }
    }

    /**
     * `dumpsys activity spock.adb.sample` on an API 34 emulator, with the sample app at
     * Home → List → Detail. The task also holds MainActivity, whose framework ReportFragment and
     * AutofillManager dumpable were what Current fragment used to report.
     */
    @Test
    fun `reports the Navigation destination and its child, not the host or framework entries`() {
        val fragments = FragmentDumpParser.parse(fixture("fragments-api34-sample-navigation.txt"))

        assertEquals(listOf("DetailFragment", "  ChildFragment"), fragments.render())
    }

    /**
     * The same app with MainActivity started on top of NavigationActivity: the resumed activity
     * has no androidx fragments, so nothing is reported — not the paused activity's fragments.
     */
    @Test
    fun `reports nothing when the resumed activity has no fragments`() {
        assertTrue(FragmentDumpParser.parse(fixture("fragments-api34-sample-main.txt")).isEmpty())
    }

    @Test
    fun `reads the older androidx entry format and drops hidden fragments`() {
        val dump = """
            |  ACTIVITY com.example.app/.MainActivity abc pid=1234
            |    Local Activity 1 State:
            |      mResumed=true mStopped=false mFinished=false
            |      Added Fragments:
            |        #0: ReportFragment{111 #0 androidx.lifecycle.LifecycleDispatcher.report_fragment_tag}
            |    Local FragmentActivity 1 State:
            |      mCreated=true mResumed=true mStopped=false    Active Fragments in 2:
            |      HomeFragment{aaa111 #0 id=0x7f0a tag=home}
            |        mHidden=false mDetached=false
            |        Child FragmentManager{3 in HomeFragment{aaa111}}:
            |          Active Fragments in 3:
            |          HiddenChild{ddd444 #0 id=0x2}
            |            mHidden=true
            |          Added Fragments:
            |            #0: HiddenChild{ddd444 #0 id=0x2}
            |      TabFragment{bbb222 #1 id=0x7f0b}
            |        mHidden=true mDetached=false
            |        Child FragmentManager{4 in TabFragment{bbb222}}:
            |          Added Fragments:
            |            #0: VisibleOnlyInsideHidden{eee555 #0}
            |      Added Fragments:
            |        #0: HomeFragment{aaa111 #0 id=0x7f0a tag=home}
            |        #1: TabFragment{bbb222 #1 id=0x7f0b}
        """.trimMargin()

        assertEquals(listOf("HomeFragment"), FragmentDumpParser.parse(dump).render())
    }

    @Test
    fun `returns empty list when the dump has no activity`() {
        assertTrue(FragmentDumpParser.parse("").isEmpty())
        assertTrue(FragmentDumpParser.parse("no activity here").isEmpty())
    }
}
