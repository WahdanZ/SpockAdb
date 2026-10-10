package spock.adb.diagnostics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.flutter.FlutterAttachOutcome
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionService
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.IdentifiedCandidate
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.FlutterFixtures
import spock.adb.flutter.analysis.FlutterProblemTypes
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.VmServiceClosedException

class FlutterSectionTest : FlutterSectionFixture() {

    @Test
    fun `every attach outcome is said in words, never as no errors`() {
        val candidate = IdentifiedCandidate(
            PastedUriDiscovery("ws://127.0.0.1:1/AbCdEfGh123=/ws").discover().single(),
            identity.copy(verifiedBy = IdentityCheck.DTD_PID_AMBIGUOUS),
        )
        val outcomes = mapOf(
            FlutterAttachOutcome.Connected(FlutterSession(), identity, reused = true) to "Connected to $APP",
            FlutterAttachOutcome.NotRunning(SERIAL, APP) to "not running",
            FlutterAttachOutcome.ReleaseBuild(SERIAL, APP) to "release build",
            FlutterAttachOutcome.NotReady("No address yet.", 1_000) to "the app is starting",
            FlutterAttachOutcome.NotReady("Android froze $APP (cached-app freezer).", 30_000, frozen = true)
                to "cached-app freezer",
            FlutterAttachOutcome.NoDdsSession(identity, FlutterSessionService.NO_DDS_MESSAGE) to "without a debugger",
            FlutterAttachOutcome.Ambiguous(listOf(candidate, candidate), "choose one") to "does not guess",
            FlutterAttachOutcome.NotFound("nothing logged an address") to "No Flutter session found",
            FlutterAttachOutcome.Failed("adb went away") to "Could not connect",
        )

        outcomes.forEach { (outcome, words) ->
            val flutter = flutterOf(report(source(outcome)))
            val attach = flutter["attach"].asString
            assertTrue(attach.contains(words, ignoreCase = true), "$outcome → $attach")
            assertFalse(attach.contains("no errors", ignoreCase = true))
            assertFalse(attach.contains("AbCdEfGh123"), "no token in $attach")
        }
    }

    @Test
    fun `when nobody asked, the section says why`() {
        val flutter = flutterOf(report(source(null, note = "No project is open.")))

        assertEquals("No project is open.", flutter["attach"].asString)
    }

    @Test
    fun `an app that is not Flutter has no flutter section at all`() {
        val report = DiagnosticCollector().collect(listOf(logs(), FlutterSection), probe(null))

        assertFalse(report.has(FlutterSection.id), "$report")
        assertFalse(report.getAsJsonObject("more").has(FlutterSection.id))
    }

    @Test
    fun `a live session reports who it is, how it was verified, and its switches`() {
        val identityJson = flutterOf(report(live())).getAsJsonObject("identity")

        assertEquals(SERIAL, identityJson["serial"].asString)
        assertEquals(4242, identityJson["pid"].asInt)
        assertEquals("dtd+pid+start", identityJson["verifiedBy"].asString)
        assertEquals("dds", identityJson["connectionKind"].asString)
        assertEquals("on", identityJson["structuredErrors"].asString)
        assertTrue(identityJson["httpRecording"].asString.startsWith("on: Spock turned it on"))
    }

    @Test
    fun `structured errors off reads framework errors from logcat, the first in full, and says so`() {
        val report = report(live(snapshot = snapshot(structuredErrors = false), flutterLog = { LOGCAT }))

        val errors = flutterOf(report).getAsJsonObject("errors")
        assertTrue(errors["source"].asString.startsWith("Structured errors are off"), "$errors")
        assertEquals(4, errors["inLogcat"].asInt)
        assertEquals(0, errors["sinceConnected"].asInt)
        val groups = errors.getAsJsonArray("groups").map { it.asJsonObject }
        assertEquals(listOf("logcat"), groups.map { it["source"].asString }.distinct())
        assertTrue(
            groups.first()["summary"].asString
                .startsWith("Exception caught by rendering library: A RenderFlex overflowed by 219 pixels"),
            "$groups",
        )
        assertEquals("10-02 14:03:07.412", groups.first()["firstSeen"].asString)
        assertEquals(2, groups.single { "tap handler" in it["summary"].asString }["count"].asInt)
        val top = report.getAsJsonArray("likelyProblems").first().asJsonObject
        assertEquals(FlutterProblemTypes.FLUTTER_ERROR, top["type"].asString)
        assertFalse(report.toString().contains("AbCdEfGh123"), "no token from the DevTools link")
    }

    @Test
    fun `a profile build reads logcat too, as it has no inspector`() {
        val profile = live(
            snapshot = snapshot(structuredErrors = null),
            buildMode = FlutterBuild.PROFILE,
            flutterLog = { LOGCAT },
        )
        val report = report(profile)

        val errors = flutterOf(report).getAsJsonObject("errors")
        assertTrue(errors["source"].asString.startsWith("A profile build has no inspector"), "$errors")
        assertEquals(4, errors["inLogcat"].asInt)
    }

    @Test
    fun `structured errors off with nothing in logcat is not proof of none`() {
        val flutter = flutterOf(report(live(snapshot = snapshot(structuredErrors = false), flutterLog = { "" })))

        val notes = notesOf(flutter)
        assertTrue(notes.any { "hold none from the app" in it && "FlutterError.onError" in it }, "$notes")
        assertFalse(flutter.toString().contains("no errors", ignoreCase = true), "$flutter")
    }

    @Test
    fun `a debug build with structured errors on and nothing in either place never says no errors`() {
        var logcatRead = false
        val flutter = flutterOf(
            report(
                live(
                    flutterLog = {
                        logcatRead = true
                        ""
                    },
                ),
            ),
        )

        assertTrue(logcatRead, "silence on the VM Service is checked against logcat")
        val notes = notesOf(flutter)
        assertTrue(
            notes.any {
                "No Flutter.Error since Spock connected" in it && "no framework error in the last" in it &&
                    "not proof of none" in it && "FlutterError.onError" in it && "Crashlytics" in it
            },
            "$notes",
        )
        assertFalse(flutter.toString().contains("no errors", ignoreCase = true), "$flutter")
    }

    @Test
    fun `an unreadable logcat is said, and still not proof of none`() {
        val notes = notesOf(flutterOf(report(live(flutterLog = { null }))))

        assertTrue(notes.any { "logcat could not be read" in it && "not proof of none" in it }, "$notes")
    }

    @Test
    fun `with structured errors on and a Flutter Error, logcat is not read`() {
        var logcatRead = false
        val flutter = flutterOf(
            report(
                live(
                    errors = listOf(error(AT)),
                    flutterLog = {
                        logcatRead = true
                        LOGCAT
                    },
                ),
            ),
        )

        assertFalse(logcatRead)
        val errors = flutter.getAsJsonObject("errors")
        assertTrue(errors["source"].asString.startsWith("Flutter.Error events"), "$errors")
        assertFalse(errors.has("inLogcat"))
    }

    @Test
    fun `structured errors on, no Flutter Error, but framework errors in logcat are listed`() {
        val flutter = flutterOf(report(live(flutterLog = { LOGCAT })))

        val errors = flutter.getAsJsonObject("errors")
        assertEquals(4, errors["inLogcat"].asInt)
        assertTrue(notesOf(flutter).any { "but logcat holds framework errors" in it }, "${notesOf(flutter)}")
    }

    @Test
    fun `an error on screen with no Flutter Error names the custom handler`() {
        val ui = section(UiSection.id) {
            JsonObject().apply { add("text", JsonArray().apply { add("A RenderFlex overflowed by 219 pixels") }) }
        }
        val report = DiagnosticCollector().collect(listOf(logs(), FlutterSection, ui), probe(live()))

        val notes = notesOf(flutterOf(report))
        assertTrue(notes.any { "The screen shows" in it && "FlutterError.onError" in it }, "$notes")
    }

    @Test
    fun `errors from before Spock connected are labelled so`() {
        val events = listOf(error(AT, "Overflow A", history = true), error(AT + 10, "Overflow A", history = true))
        val flutter = flutterOf(report(live(errors = events)))

        val errors = flutter.getAsJsonObject("errors")
        assertEquals(0, errors["sinceConnected"].asInt)
        assertEquals(2, errors["beforeSpockConnected"].asInt)
        val group = errors.getAsJsonArray("groups").single().asJsonObject
        assertTrue(group["summary"].asString.endsWith("(before Spock connected)"), "$group")
        assertEquals(2, group["beforeSpockConnected"].asInt)
    }

    @Test
    fun `an error pairs with the log problem a second away, not five`() {
        val near = logProblem("exception: near", "10-02 14:00:01.000")
        val far = logProblem("exception: far", "10-02 14:00:05.000")
        val report = collect(live(errors = listOf(error(AT))), near, far)

        val group = groupOf(report)
        val nearby = group.getAsJsonArray("nearbyLogs").map { it.asString }
        assertEquals(listOf(idOf(report, "exception: near")), nearby)
        assertEquals(idOf(report, "A RenderFlex overflowed"), group["problem"].asString)
    }

    @Test
    fun `logcat stamps are moved by the device's zone, not read as UTC`() {
        // 12:00:00.5 is what the device logged half a second after the error only if read as UTC:
        // in Berlin (+0200) it is two hours earlier, and must not pair.
        val asIfUtc = logProblem("exception: misread", "10-02 12:00:00.500")
        val report = collect(live(errors = listOf(error(AT))), asIfUtc)

        assertEquals(0, groupOf(report).getAsJsonArray("nearbyLogs").size())
    }

    @Test
    fun `the window is widened by the clock's uncertainty`() {
        val edge = logProblem("exception: edge", "10-02 14:00:02.300")

        fun paired(uncertainty: Long): Int {
            val time = clock.copy(uncertaintyMs = uncertainty)
            val report = DiagnosticCollector().collect(
                listOf(logs(edge), FlutterSection),
                probe(live(errors = listOf(error(AT)), deviceTime = time)),
            )
            return groupOf(report).getAsJsonArray("nearbyLogs").size()
        }

        assertEquals(0, paired(50))
        assertEquals(1, paired(500))
    }

    @Test
    fun `without a measured clock no lines are paired, and the section says so`() {
        val near = logProblem("exception: near", "10-02 14:00:01.000")
        val report = DiagnosticCollector().collect(
            listOf(logs(near), FlutterSection),
            probe(live(errors = listOf(error(AT)), deviceTime = null)),
        )

        assertFalse(groupOf(report).has("nearbyLogs"))
        assertTrue(flutterOf(report).getAsJsonObject("clock")["note"].asString.contains("not measured"))
    }

    @Test
    fun `the section is bounded`() {
        val events = (1..12).map {
            error(AT + it, "Distinct failure ${"x".repeat(it)}", widget = "lib/a.dart:$it:1")
        }
        val errors = flutterOf(report(live(errors = events))).getAsJsonObject("errors")

        assertEquals(FlutterSection.MAX_ERROR_GROUPS, errors.getAsJsonArray("groups").size())
        assertEquals(12 - FlutterSection.MAX_ERROR_GROUPS, errors["moreGroups"].asInt)
    }

    @Test
    fun `failed requests come from the HTTP profile, one per request id`() {
        val profile = FlutterFixtures.json("httpProfile.json")
        val first = profile.getAsJsonArray("requests").first().asJsonObject
        profile.getAsJsonArray("requests").add(first.deepCopy())

        val report = report(live(httpProfile = { profile }))
        val http = flutterOf(report).getAsJsonObject("http")

        val failed = http["failed"].asInt
        assertTrue(failed >= 1, "$http")
        assertEquals(minOf(failed, FlutterSection.MAX_HTTP_FAILURES), http.getAsJsonArray("failures").size())
        assertTrue(http["failures"].asJsonArray.any { "/status/500" in it.asString }, "$http")
        val requestIds = profile.getAsJsonArray("requests").map { it.asJsonObject["id"].asString }
        assertEquals(requestIds.distinct().size, http["requests"].asInt, "deduped by id")
    }

    @Test
    fun `HTTP that is not recorded is not read, and says why`() {
        var read = false
        val off = snapshot(recording = HttpRecording.Off(HttpRecording.Reason.SETTING_OFF))
        val reading = {
            read = true
            JsonObject()
        }
        val http = flutterOf(report(live(snapshot = off, httpProfile = reading)))
            .getAsJsonObject("http")

        assertFalse(read)
        assertTrue(http["recording"].asString.contains("Record Flutter HTTP traffic automatically"))
    }

    @Test
    fun `a profile that cannot be read is a note, not a failed section`() {
        val http = flutterOf(report(live(httpProfile = { throw VmServiceClosedException("gone") })))
            .getAsJsonObject("http")

        assertTrue(http["note"].asString.contains("Could not read the HTTP profile"))
    }

    @Test
    fun `a Flutter error ranks above a failed request, and frames below exceptions`() {
        val problems = listOf(
            LikelyProblem(LogProblemExtractor.TYPE_NETWORK, Severity.ERROR, "GET / returned HTTP 500"),
            LikelyProblem(FlutterProblemTypes.FLUTTER_ERROR, Severity.ERROR, "overflow"),
            LikelyProblem(FlutterProblemTypes.JANK, Severity.WARNING, "janky"),
            LikelyProblem(LogProblemExtractor.TYPE_EXCEPTION, Severity.WARNING, "an exception"),
            LikelyProblem("permission", Severity.INFO, "denied"),
            LikelyProblem(FlutterProblemTypes.FREQUENT_REBUILDS, Severity.INFO, "rebuilds"),
        )
        val report = DiagnosticCollector().collect(listOf(section("x", problems = problems)), probe(null))

        val listed = report.getAsJsonArray("likelyProblems").map { it.asJsonObject }
        assertEquals(
            listOf("overflow", "GET / returned HTTP 500", "an exception", "janky", "rebuilds", "denied"),
            listed.map { it["summary"].asString },
        )
        assertEquals((1..6).map { "p$it" }, listed.map { it["id"].asString })
    }

    @Test
    fun `the Diagnose tab shows a Flutter error with its logcat context`() {
        val near = logProblem("exception: near", "10-02 14:00:01.000")
        val report = collect(live(errors = listOf(error(AT))), near)

        val diagnosis = ScreenDiagnosis(report)
        val errorId = idOf(report, "A RenderFlex overflowed")
        assertEquals(listOf("exception: near"), diagnosis.nearby[errorId]?.map { it.summary })
        assertTrue(DiagnosePanel.SummaryHtml.render(diagnosis).contains("In logcat around it"))
        assertTrue(diagnosis.facts.any { it.first == "Flutter" })
    }

    @Test
    fun `debug frames are reported as not representative`() {
        val frames = FlutterFixtures.events(FlutterExtensionEvent.FRAME)
        val flutter = flutterOf(report(live(frames = frames)))

        val json = flutter.getAsJsonObject("frames")
        assertEquals(frames.size, json["frames"].asInt)
        assertTrue(json["note"].asString.contains("not representative"))
    }

    @Test
    fun `a log problem pairs on any of its lines, not only its last`() {
        val repeated = logProblem("exception: repeated", "10-02 14:00:30.000")
            .copy(count = 2, seenAt = listOf("10-02 14:00:01.000", "10-02 14:00:30.000"))
        val report = collect(live(errors = listOf(error(AT))), repeated)

        val nearby = groupOf(report).getAsJsonArray("nearbyLogs").map { it.asString }
        assertEquals(listOf(idOf(report, "exception: repeated")), nearby)
    }

    @Test
    fun `a log problem between two far apart occurrences of a long-lived error is not paired with it`() {
        // Seen at the start and ten minutes later: the warning five minutes in is beside neither.
        val errors = listOf(error(AT), error(AT + TEN_MINUTES))
        val middle = logProblem("exception: middle", "10-02 14:05:00.000")
        val report = collect(live(errors = errors), middle)

        assertEquals(2, groupOf(report)["count"].asInt)
        assertEquals(0, groupOf(report).getAsJsonArray("nearbyLogs").size())
    }

    @Test
    fun `a log problem beside the second occurrence of an error is paired with it`() {
        val errors = listOf(error(AT), error(AT + TEN_MINUTES))
        val middle = logProblem("exception: middle", "10-02 14:05:00.000")
        val beside = logProblem("exception: beside the second", "10-02 14:10:01.000")
        val report = collect(live(errors = errors), middle, beside)

        val nearby = groupOf(report).getAsJsonArray("nearbyLogs").map { it.asString }
        assertEquals(listOf(idOf(report, "exception: beside the second")), nearby)
    }

    @Test
    fun `an error seen more often than the occurrences it keeps still pairs with the warning before its first`() {
        // The first at AT, then 120 more from a minute later: only the latest 100 occurrences are kept.
        val errors = listOf(error(AT)) + (1..120).map { error(AT + 60_000 + it * 1_000L) }
        val before = logProblem("exception: before the first", "10-02 13:59:59.500")
        val report = collect(live(errors = errors), before)

        assertEquals(121, groupOf(report)["count"].asInt)
        val nearby = groupOf(report).getAsJsonArray("nearbyLogs").map { it.asString }
        assertEquals(listOf(idOf(report, "exception: before the first")), nearby)
    }

    @Test
    fun `an app warning a second before the error is paired and shown, above older platform noise`() {
        // The device gate of 2026-10-02: start-up noise from minutes earlier filled the top ten.
        val noise = (1..12).map {
            LikelyProblem(
                "log",
                Severity.WARNING,
                "ziparchive: Unable to open '/data/app/base.dm' #$it",
                count = 3,
                lastSeen = "10-02 13:53:00.000",
                section = LogsSection.id,
            )
        }
        val app = LikelyProblem(
            "log",
            Severity.WARNING,
            "SpockSample: Layout fixture: the overflow was just shown",
            lastSeen = "10-02 13:59:58.700",
            section = LogsSection.id,
        )
        val report = collect(live(errors = listOf(error(AT))), *(noise + app).toTypedArray())

        val listed = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["summary"].asString }
        assertTrue(listed[0].contains("A RenderFlex overflowed"), "$listed")
        assertEquals(app.summary, listed[1])
        assertEquals(listOf("p2"), groupOf(report).getAsJsonArray("nearbyLogs").map { it.asString })
        val html = DiagnosePanel.SummaryHtml.render(ScreenDiagnosis(report))
        assertTrue(html.contains("In logcat around it"), html)
        assertTrue(html.contains("10-02 13:59:58.700 SpockSample: Layout fixture"), html)
    }

    @Test
    fun `at most five log problems are paired with an error, the closest first`() {
        // Eight inside the window, 100 ms apart, listed farthest first.
        val near = (8 downTo 1).map { logProblem("exception: near $it", "10-02 14:00:00.${it}00") }
        val report = collect(live(errors = listOf(error(AT))), *near.toTypedArray())

        val ids = groupOf(report).getAsJsonArray("nearbyLogs").map { it.asString }
        val summaries = ids.map { id -> problemById(report, id)["summary"].asString }
        assertEquals((1..FlutterSection.MAX_NEARBY_LOGS).map { "exception: near $it" }, summaries)
    }

    @Test
    fun `a paired problem that already ranks higher is not moved down`() {
        // An ANR: it outranks a Flutter error, and is not a native failure that would merge with it.
        val anr = LikelyProblem(
            LogProblemExtractor.TYPE_ANR,
            Severity.ERROR,
            "App not responding: spock.adb.spock_flutter_sample",
            lastSeen = "10-02 14:00:01.000",
            section = LogsSection.id,
        )
        val report = collect(live(errors = listOf(error(AT))), anr)

        assertEquals(anr.summary, problemById(report, "p1")["summary"].asString)
        assertEquals(listOf("p1"), groupOf(report).getAsJsonArray("nearbyLogs").map { it.asString })
    }

    private companion object {
        const val TEN_MINUTES = 600_000L
    }
}
