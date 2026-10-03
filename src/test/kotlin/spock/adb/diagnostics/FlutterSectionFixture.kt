package spock.adb.diagnostics

import com.android.ddmlib.IDevice
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.mockk.mockk
import spock.adb.diagnostics.LikelyProblem.Severity
import spock.adb.flutter.AppIdentity
import spock.adb.flutter.DeviceTime
import spock.adb.flutter.FlutterAttachOutcome
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterEventLog
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionSnapshot
import spock.adb.flutter.HttpRecording
import spock.adb.flutter.IdentityCheck
import spock.adb.flutter.SessionState
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.FlutterProblemTypes
import spock.adb.flutter.vmservice.ConnectionKind
import java.time.Instant
import java.time.ZoneOffset

/** What the `flutter` section's tests share: a live session of plain values, and the collector around it. */
abstract class FlutterSectionFixture {

    protected val identity = AppIdentity(SERIAL, APP, 4242, IdentityCheck.DTD_PID_START)
    protected val berlin = ZoneOffset.ofHours(2)
    protected val clock = DeviceTime(epochOffsetMs = 0, uncertaintyMs = 50, zone = berlin)

    protected fun report(source: FlutterDiagnosticSource): JsonObject = collect(source)

    protected fun collect(source: FlutterDiagnosticSource, vararg logProblems: LikelyProblem): JsonObject =
        DiagnosticCollector().collect(listOf(logs(*logProblems), FlutterSection), probe(source))

    protected fun probe(source: FlutterDiagnosticSource?, logWindowLines: Int = AndroidProbe.DEFAULT_LOG_WINDOW_LINES) =
        AndroidProbe(mockk<IDevice>(relaxed = true), SERIAL, APP, logWindowLines, flutter = source)

    protected fun source(outcome: FlutterAttachOutcome?, note: String? = null) =
        FlutterDiagnosticSource(APP, FlutterBuild.DEBUG, outcome, note, hostNowMs = AT)

    protected fun live(
        errors: List<FlutterExtensionEvent> = emptyList(),
        frames: List<FlutterExtensionEvent> = emptyList(),
        snapshot: FlutterSessionSnapshot = snapshot(),
        deviceTime: DeviceTime? = clock,
        httpProfile: () -> JsonObject? = { JsonObject() },
        buildMode: FlutterBuild = FlutterBuild.DEBUG,
        flutterLog: (Int) -> String? = { "" },
    ): FlutterDiagnosticSource {
        val reads = object : FlutterDiagnosticSource.Reads {
            override fun refreshRate(): Double? = 60.0
            override fun httpProfile(): JsonObject? = httpProfile()
            override fun flutterLog(lines: Int): String? = flutterLog(lines)
        }
        val contents = FlutterEventLog.Contents(errors, frames, emptyList(), emptyMap())
        return FlutterDiagnosticSource(
            APP,
            buildMode,
            FlutterAttachOutcome.Connected(FlutterSession(), identity, reused = true),
            live = FlutterDiagnosticSource.Live(identity, snapshot, buildMode, contents, deviceTime, reads),
            hostNowMs = AT,
        )
    }

    protected fun snapshot(
        structuredErrors: Boolean? = true,
        recording: HttpRecording? = HttpRecording.EnabledBySpock,
        connectedAt: Long = AT - 60_000,
    ) = FlutterSessionSnapshot(
        state = SessionState.Connected("isolates/1"),
        structuredErrorsEnabled = structuredErrors,
        connectionKind = ConnectionKind.DDS,
        connectedAtHostMs = connectedAt,
        vmPid = 4242,
        httpRecording = recording,
    )

    protected fun error(
        timestampMs: Long,
        headline: String = "A RenderFlex overflowed by 219 pixels on the right.",
        history: Boolean = false,
        widget: String? = null,
    ): FlutterExtensionEvent {
        val properties = JsonArray().apply {
            add(
                JsonObject().apply {
                    addProperty("type", "ErrorSummary")
                    addProperty("description", headline)
                },
            )
            widget?.let { location ->
                add(
                    JsonObject().apply {
                        addProperty("name", "The relevant error-causing widget was")
                        add(
                            "children",
                            JsonArray().apply {
                                add(JsonObject().apply { addProperty("description", "Row Row:file:///app/$location") })
                            },
                        )
                    },
                )
            }
        }
        val data = JsonObject().apply {
            addProperty("description", "Exception caught by rendering library")
            add("properties", properties)
        }
        return FlutterExtensionEvent(FlutterExtensionEvent.ERROR, timestampMs, "isolates/1", data, history)
    }

    protected fun logProblem(summary: String, stamp: String) = LikelyProblem(
        LogProblemExtractor.TYPE_EXCEPTION,
        Severity.WARNING,
        summary,
        lastSeen = stamp,
        section = LogsSection.id,
    )

    protected fun logs(vararg problems: LikelyProblem) = section(LogsSection.id, problems = problems.toList())

    protected fun section(
        sectionId: String,
        problems: List<LikelyProblem> = emptyList(),
        data: () -> JsonObject = { JsonObject() },
    ) = object : DiagnosticSection<AndroidProbe> {
        override val id = sectionId
        override val detail: DetailRef? = null
        override fun collect(probe: AndroidProbe) = SectionReport(data(), problems)
    }

    protected fun flutterOf(report: JsonObject): JsonObject = report.getAsJsonObject(FlutterSection.id)

    protected fun notesOf(flutter: JsonObject): List<String> =
        flutter.getAsJsonArray("notes")?.map { it.asString }.orEmpty()

    protected fun groupOf(report: JsonObject): JsonObject =
        flutterOf(report).getAsJsonObject("errors").getAsJsonArray("groups").single().asJsonObject

    protected fun idOf(report: JsonObject, summaryStart: String): String =
        report.getAsJsonArray("likelyProblems").map { it.asJsonObject }
            .single { it["summary"].asString.contains(summaryStart) }["id"].asString

    protected fun crashAt(time: String) =
        nativeProblem(LogProblemExtractor.TYPE_CRASH, "App crashed: java.lang.IllegalStateException: boom", time)

    protected fun nativeProblem(type: String, summary: String, time: String) = LikelyProblem(
        type,
        Severity.ERROR,
        summary,
        lastSeen = "10-02 $time",
        section = LogsSection.id,
    )

    protected fun problemById(report: JsonObject, id: String): JsonObject =
        report.getAsJsonArray("likelyProblems").map { it.asJsonObject }.single { it["id"].asString == id }

    /** A section whose merge runs before Flutter's and takes every Flutter error into its own problem. */
    protected fun mergesFlutterErrors() = object : DiagnosticSection<AndroidProbe> {
        override val id = "other"
        override val detail: DetailRef? = null
        override fun collect(probe: AndroidProbe) = SectionReport(
            JsonObject(),
            emptyList(),
            merges = { all ->
                listOf(
                    LikelyProblem(
                        "other",
                        Severity.ERROR,
                        "another merge",
                        parts = all.filter { it.type == FlutterProblemTypes.FLUTTER_ERROR },
                    ),
                )
            },
        )
    }

    companion object {
        const val SERIAL = "emulator-5554"
        const val APP = "spock.adb.spock_flutter_sample"

        /** 14:00:00 in Berlin: the error's device epoch ms. */
        val AT: Long = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()

        /** What pid 4242 printed with structured errors off: one report in full, three repeats. */
        val LOGCAT: String =
            requireNotNull(FlutterSectionFixture::class.java.getResource("/flutter/logcat-structured-errors-off.txt"))
                .readText()
    }
}
