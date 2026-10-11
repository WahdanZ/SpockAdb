package spock.adb.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.analysis.FlutterProblemTypes

/** A Dart error and a native failure beside it, listed as one problem (plan P5b): when they merge, and when not. */
class FlutterSectionCrossLayerTest : FlutterSectionFixture() {

    @Test
    fun `a Dart error and a native crash a second apart are one problem naming both layers`() {
        val crash = crashAt("14:00:01.000")
        val report = collect(live(errors = listOf(error(AT))), crash)

        val listed = report.getAsJsonArray("likelyProblems").map { it.asJsonObject }
        val combined = listed.single { it["type"].asString == FlutterProblemTypes.CROSS_LAYER }
        val summary = combined["summary"].asString
        assertTrue(summary.startsWith("In Dart and on Android, 1.0 s apart"), summary)
        assertTrue("Dart: Exception caught by rendering library: A RenderFlex overflowed" in summary, summary)
        assertTrue("Android: App crashed: java.lang.IllegalStateException: boom" in summary, summary)
        assertTrue(summary.length <= DiagnosticShell.MAX_VALUE_CHARS, "${summary.length}")
        assertEquals("error", combined["severity"].asString)
        assertEquals(FlutterSection.id, combined["section"].asString)
        // One problem, not two: neither layer is listed on its own.
        assertEquals(1, listed.size, "$listed")
        val parts = combined.getAsJsonArray("parts").map { it.asJsonObject }
        assertEquals(
            listOf(FlutterProblemTypes.FLUTTER_ERROR, LogProblemExtractor.TYPE_CRASH),
            parts.map { it["type"].asString },
        )
        assertEquals(listOf(FlutterSection.id, LogsSection.id), parts.map { it["section"].asString })
        assertTrue(parts.none { it.has("id") }, "parts are not listed apart")
        val group = groupOf(report)
        assertEquals(combined["id"].asString, group["problem"].asString)
        assertEquals(crash.summary, group["crossLayer"].asString)
    }

    @Test
    fun `a native crash beside the first of more occurrences than are kept still merges`() {
        val errors = listOf(error(AT)) + (1..120).map { error(AT + 60_000 + it * 1_000L) }
        val report = collect(live(errors = errors), crashAt("13:59:59.500"))

        val types = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["type"].asString }
        assertTrue(FlutterProblemTypes.CROSS_LAYER in types, "$types")
    }

    @Test
    fun `a native crash five seconds away stays a problem of its own`() {
        val crash = crashAt("14:00:05.000")
        val report = collect(live(errors = listOf(error(AT))), crash)

        val types = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["type"].asString }
        assertEquals(listOf(LogProblemExtractor.TYPE_CRASH, FlutterProblemTypes.FLUTTER_ERROR), types)
        assertFalse(groupOf(report).has("crossLayer"))
    }

    @Test
    fun `a MissingPluginException or DartMessenger channel failure beside a Dart error is one problem`() {
        val missing = nativeProblem(
            LogProblemExtractor.TYPE_FLUTTER_PLUGIN,
            "Platform channel handler for throwChecked on spock.sample/native threw java.io.IOException: " +
                "Sample checked exception (inferred from DartMessenger's log)",
            "13:59:59.700",
        )
        val messenger = nativeProblem(
            LogProblemExtractor.TYPE_EXCEPTION,
            "DartMessenger: Uncaught exception in binary message listener — java.io.IOException: other",
            "14:00:00.200",
        )

        fun crossLayerOf(native: LikelyProblem) = collect(live(errors = listOf(error(AT))), native)
            .getAsJsonArray("likelyProblems").map { it.asJsonObject }
            .single { it["type"].asString == FlutterProblemTypes.CROSS_LAYER }

        assertTrue(crossLayerOf(missing)["summary"].asString.contains("0.3 s apart"))
        assertTrue(crossLayerOf(messenger)["summary"].asString.contains("Android: DartMessenger: Uncaught exception"))
    }

    @Test
    fun `a plain log warning beside a Dart error is context, not a cross-layer problem`() {
        val near = logProblem("exception: near", "10-02 14:00:01.000")
        val report = collect(live(errors = listOf(error(AT))), near)

        val types = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["type"].asString }
        assertFalse(FlutterProblemTypes.CROSS_LAYER in types, "$types")
    }

    @Test
    fun `the closest native failure joins the error, and the other stays nearby context`() {
        val closer = nativeProblem(LogProblemExtractor.TYPE_CRASH, "App crashed: closer", "14:00:00.300")
        val farther =
            nativeProblem(LogProblemExtractor.TYPE_FLUTTER_PLUGIN, "Flutter plugin not registered: x", "14:00:01.500")
        val report = collect(live(errors = listOf(error(AT))), farther, closer)

        val listed = report.getAsJsonArray("likelyProblems").map { it.asJsonObject }
        val combined = listed.single { it["type"].asString == FlutterProblemTypes.CROSS_LAYER }
        assertTrue(combined["summary"].asString.contains("Android: App crashed: closer"), "$combined")
        val group = groupOf(report)
        val nearby = group.getAsJsonArray("nearbyLogs").map { it.asString }
        assertEquals(listOf(idOf(report, "Flutter plugin not registered: x")), nearby)
    }

    @Test
    fun `without a measured clock nothing is merged`() {
        val crash = nativeProblem(LogProblemExtractor.TYPE_CRASH, "App crashed: boom", "14:00:01.000")
        val report = DiagnosticCollector().collect(
            listOf(logs(crash), FlutterSection),
            probe(live(errors = listOf(error(AT)), deviceTime = null)),
        )

        val types = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["type"].asString }
        assertFalse(FlutterProblemTypes.CROSS_LAYER in types, "$types")
    }

    @Test
    fun `an error seen at the start and the end of ten minutes does not merge with a crash in between`() {
        val errors = listOf(error(AT), error(AT + 600_000))
        val report = collect(live(errors = errors), crashAt("14:05:00.000"))

        val types = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["type"].asString }
        assertFalse(FlutterProblemTypes.CROSS_LAYER in types, "$types")
        assertFalse(groupOf(report).has("crossLayer"))
    }

    @Test
    fun `a merged problem counts the times both layers were seen together, not the Dart error's own count`() {
        val errors = listOf(error(AT), error(AT + 600_000))
        val report = collect(live(errors = errors), crashAt("14:00:01.000"))

        val combined = report.getAsJsonArray("likelyProblems").map { it.asJsonObject }
            .single { it["type"].asString == FlutterProblemTypes.CROSS_LAYER }
        assertFalse(combined.has("count"), "seen together once: $combined")
        val dart = combined.getAsJsonArray("parts").first().asJsonObject
        assertEquals(2, dart["count"].asInt)
    }

    @Test
    fun `a merge the collector dropped leaves no crossLayer on the error`() {
        // A section ahead of this one already took the Flutter error into a problem of its own.
        val report = DiagnosticCollector().collect(
            listOf(logs(crashAt("14:00:01.000")), mergesFlutterErrors(), FlutterSection),
            probe(live(errors = listOf(error(AT)))),
        )

        assertFalse(groupOf(report).has("crossLayer"), "${groupOf(report)}")
        val types = report.getAsJsonArray("likelyProblems").map { it.asJsonObject["type"].asString }
        assertFalse(FlutterProblemTypes.CROSS_LAYER in types, "$types")
    }
}
