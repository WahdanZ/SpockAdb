package spock.adb.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterBuild
import java.time.Instant

/** Where the `flutter` section read the errors it lists, and the logcat window it read them from. */
class FlutterSectionErrorSourceTest : FlutterSectionFixture() {

    @Test
    fun `errors source says logcat when it stood in for a silent VM Service`() {
        val errors = flutterOf(report(live(flutterLog = { LOGCAT }))).getAsJsonObject("errors")

        assertTrue(
            errors["source"].asString.startsWith("Logcat: structured errors are on, yet no Flutter.Error arrived"),
            "$errors",
        )
        val sources = errors.getAsJsonArray("groups").map { it.asJsonObject["source"].asString }
        assertEquals(listOf("logcat"), sources.distinct())
    }

    @Test
    fun `errors source says the VM Service when its events are what is listed`() {
        val errors = flutterOf(report(live(errors = listOf(error(AT))))).getAsJsonObject("errors")

        assertTrue(errors["source"].asString.startsWith("Flutter.Error events from the VM Service"), "$errors")
        assertEquals("vmService", errors.getAsJsonArray("groups").single().asJsonObject["source"].asString)
    }

    @Test
    fun `errors source says both when structured errors were switched, and a group from both says so`() {
        // Structured errors were on (an event), then switched off (Flutter printed the same overflow to logcat).
        val earlier = error(
            Instant.parse("2026-10-02T12:03:00Z").toEpochMilli(),
            widget = "lib/fixtures/layout.dart:30:17",
        )
        val errors = flutterOf(
            report(
                live(errors = listOf(earlier), snapshot = snapshot(structuredErrors = false), flutterLog = { LOGCAT }),
            ),
        ).getAsJsonObject("errors")

        assertTrue(errors["source"].asString.startsWith("Both"), "$errors")
        val overflow = errors.getAsJsonArray("groups").map { it.asJsonObject }
            .single { "RenderFlex" in it["summary"].asString }
        assertEquals("vmService and logcat", overflow["source"].asString)
        assertEquals(2, overflow["count"].asInt)
    }

    @Test
    fun `errors source says the VM Service when its events are listed though structured errors are off now`() {
        // Two overflows arrived as events, then structured errors went off; logcat holds nothing yet.
        val errors = flutterOf(
            report(
                live(
                    errors = listOf(error(AT), error(AT + 1_000)),
                    snapshot = snapshot(structuredErrors = false),
                    flutterLog = { "" },
                ),
            ),
        ).getAsJsonObject("errors")

        val source = errors["source"].asString
        assertTrue(source.startsWith("Flutter.Error events from the VM Service"), "$errors")
        assertTrue(source.contains("off now") && source.endsWith("where none are yet"), "$errors")
        assertFalse(source.contains("this section reads them there"), "$errors")
        assertEquals(0, errors["inLogcat"].asInt)
        assertEquals(2, errors["sinceConnected"].asInt)
        assertEquals("vmService", errors.getAsJsonArray("groups").single().asJsonObject["source"].asString)
    }

    @Test
    fun `a profile build's message-less repeat is listed apart from the error before it, with a note`() {
        val profile = listOf(
            "10-02 14:03:07.412  4242  4242 I flutter : Bad state: Sample error thrown in a tap handler",
            "10-02 14:03:07.413  4242  4242 I flutter : #0      _ErrorsScreenState.build.<anonymous closure> " +
                "(package:spock_flutter_sample/fixtures/errors.dart:68)",
            "10-02 14:03:09.208  4242  4242 I flutter : Another exception was thrown: Instance of 'ErrorSummary'",
        ).joinToString("\n")
        val flutter = flutterOf(
            report(
                live(
                    snapshot = snapshot(structuredErrors = null),
                    buildMode = FlutterBuild.PROFILE,
                    flutterLog = { profile },
                ),
            ),
        )

        val groups = flutter.getAsJsonObject("errors").getAsJsonArray("groups").map { it.asJsonObject }
        assertEquals(listOf(1, 1), groups.map { it["count"].asInt })
        assertTrue(groups[1]["summary"].asString.startsWith("Another framework error"), "$groups")
        val notes = notesOf(flutter)
        assertTrue(notes.any { "only in a debug build" in it && "FlutterError.onError" in it }, "$notes")
    }

    @Test
    fun `no message-less note when every repeat says what it was`() {
        val flutter = flutterOf(report(live(snapshot = snapshot(structuredErrors = false), flutterLog = { LOGCAT })))

        assertFalse(notesOf(flutter).any { "without their message" in it }, "${notesOf(flutter)}")
    }

    @Test
    fun `with structured errors not known yet the source does not claim they are on`() {
        val errors = flutterOf(report(live(snapshot = snapshot(structuredErrors = null)))).getAsJsonObject("errors")

        assertTrue(errors["source"].asString.contains("not known yet"), "$errors")
        assertFalse(errors["source"].asString.contains(": structured errors are on"), "$errors")
    }

    @Test
    fun `a logcat error printed before Spock connected is marked as such`() {
        // Spock connected at 14:03:08 device time: the overflow at 14:03:07.412 came before it.
        val connected = Instant.parse("2026-10-02T12:03:08Z").toEpochMilli()
        val flutter = flutterOf(
            report(
                live(snapshot = snapshot(structuredErrors = false, connectedAt = connected), flutterLog = { LOGCAT }),
            ),
        )

        val groups = flutter.getAsJsonObject("errors").getAsJsonArray("groups").map { it.asJsonObject }
        val overflow = groups.single { "RenderFlex" in it["summary"].asString }
        assertTrue(overflow["summary"].asString.endsWith("(before Spock connected)"), "$overflow")
        val tap = groups.single { "tap handler" in it["summary"].asString }
        assertFalse(tap["summary"].asString.contains("before Spock connected"), "$tap")
    }

    @Test
    fun `logcat is read over the window the report was asked for`() {
        val asked = mutableListOf<Int>()
        val report = DiagnosticCollector().collect(
            listOf(logs(), FlutterSection),
            probe(
                live(
                    snapshot = snapshot(structuredErrors = false),
                    flutterLog = {
                        asked += it
                        ""
                    },
                ),
                logWindowLines = 300,
            ),
        )

        assertEquals(listOf(300), asked)
        val notes = notesOf(flutterOf(report))
        assertTrue(notes.any { "the last 300 lines of logcat" in it }, "$notes")
    }
}
