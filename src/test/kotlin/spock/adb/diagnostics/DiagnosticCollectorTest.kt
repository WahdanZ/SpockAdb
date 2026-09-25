package spock.adb.diagnostics

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.LikelyProblem.Severity

/**
 * The collector is the extension point: these tests use sections that do not exist in the
 * plugin, which is the point — a new section needs no change here, and nothing here needs a
 * device or an IDE.
 */
class DiagnosticCollectorTest {

    private val probe = DiagnosticProbe(mockk<IDevice>(relaxed = true), "emulator-5554", "com.example.app")

    private fun section(
        id: String,
        data: JsonObject = JsonObject().apply { addProperty("ok", true) },
        problems: List<LikelyProblem> = emptyList(),
        detail: DetailRef? = DetailRef("android_get_$id"),
        failure: Throwable? = null,
    ) = object : DiagnosticSection {
        override val id = id
        override val detail = detail
        override fun collect(probe: DiagnosticProbe): SectionReport {
            failure?.let { throw it }
            return SectionReport(data, problems)
        }
    }

    @Test
    fun `a new section appears under its id with no change to the collector`() {
        val report = DiagnosticCollector().collect(listOf(section("network")), probe)

        assertTrue(report["network"].asJsonObject["ok"].asBoolean)
        assertEquals("android_get_network", report["more"].asJsonObject["network"].asJsonObject["tool"].asString)
    }

    @Test
    fun `problems are ranked by severity, then by what explains most, then by count`() {
        val report = DiagnosticCollector().collect(
            listOf(
                section(
                    "a",
                    problems = listOf(
                        LikelyProblem("log", Severity.WARNING, "warn", count = 50),
                        LikelyProblem("log", Severity.ERROR, "frequent error", count = 9),
                        LikelyProblem("crash", Severity.ERROR, "crash"),
                        LikelyProblem("accessibility", Severity.INFO, "a11y"),
                    ),
                ),
            ),
            probe,
        )

        val summaries = report["likelyProblems"].asJsonArray.map { it.asJsonObject["summary"].asString }
        assertEquals(listOf("crash", "frequent error", "warn", "a11y"), summaries)
    }

    @Test
    fun `the problem list is capped and says how many it left out`() {
        val many = (1..25).map { LikelyProblem("log", Severity.ERROR, "error $it") }

        val report = DiagnosticCollector().collect(listOf(section("a", problems = many)), probe)

        assertEquals(DiagnosticCollector.MAX_PROBLEMS, report["likelyProblems"].asJsonArray.size())
        assertEquals(25 - DiagnosticCollector.MAX_PROBLEMS, report["moreProblems"].asInt)
    }

    @Test
    fun `a failing section is reported in place and does not cost the others`() {
        val report = DiagnosticCollector().collect(
            listOf(section("broken", failure = IllegalStateException("screen is off")), section("fine")),
            probe,
        )

        assertEquals("screen is off", report["sectionErrors"].asJsonObject["broken"].asString)
        assertTrue(report.has("fine"))
        assertFalse(report.has("broken"))
    }

    @Test
    fun `sections past the time budget are skipped and say so`() {
        var now = 0L
        val slow = object : DiagnosticSection {
            override val id = "slow"
            override val detail: DetailRef? = null
            override fun collect(probe: DiagnosticProbe): SectionReport {
                now += 10
                return SectionReport(JsonObject())
            }
        }

        val report = DiagnosticCollector(
            budgetNanos = 5,
            nanoTime = { now }
        ).collect(listOf(slow, section("late")), probe)

        assertTrue(report.has("slow"))
        assertTrue(report["sectionErrors"].asJsonObject["late"].asString.startsWith("Skipped"))
    }

    @Test
    fun `an oversized report drops the least important sections whole, never cutting JSON`() {
        val big = JsonObject().apply { addProperty("blob", "x".repeat(3_000)) }
        val report = DiagnosticCollector(maxChars = 4_000).collect(
            listOf(section("first", big), section("second", big), section("third", big)),
            probe,
        )

        assertTrue(DiagnosticCollector.render(report).length <= 4_000)
        assertTrue(report.has("first"), "the most important section survives")
        assertEquals(listOf("third", "second"), report["omittedForSize"].asJsonArray.map { it.asString })
        assertTrue(report["more"].asJsonObject.has("third"), "a dropped section still says where its data is")
    }

    @Test
    fun `detail references are pointed at the same app`() {
        val report = DiagnosticCollector().collect(listOf(LogsSectionStub), probe)

        val arguments = report["more"].asJsonObject["logs"].asJsonObject["arguments"].asJsonObject
        assertEquals("com.example.app", arguments["packageName"].asString)
        assertEquals("W", arguments["minLevel"].asString)
    }

    /** The real logs section's id and detail, without its device read. */
    private object LogsSectionStub : DiagnosticSection {
        override val id = LogsSection.id
        override val detail = LogsSection.detail
        override fun collect(probe: DiagnosticProbe) = SectionReport(JsonObject())
    }

    @Test
    fun `a resumed activity is parsed from either dumpsys line`() {
        assertEquals(
            ScreenSection.Resumed("com.example.app", "com.example.app.ui.Checkout"),
            ScreenSection.parseResumed("  mResumedActivity: ActivityRecord{9f1c u0 com.example.app/.ui.Checkout t12}"),
        )
        assertEquals(
            ScreenSection.Resumed("com.example.app", "com.other.Launcher"),
            ScreenSection.parseResumed(
                "  topResumedActivity=ActivityRecord{1 u0 com.example.app/com.other.Launcher t3}"
            ),
        )
        assertEquals(null, ScreenSection.parseResumed(""))
    }
}
