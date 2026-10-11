package spock.adb.uitree

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.device.ops.RecompositionException
import spock.adb.flutter.FlutterRebuildRecorder
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.RebuildTracker

/**
 * What the Recompositions tab and `android_get_recomposition_counts` both record: Flutter widget
 * rebuilds for an app with a live Flutter session, Compose for any other — including a Compose
 * host that embeds a Flutter module and has no Flutter session.
 */
class RecompositionRecordingTest {

    private var composeRecorded = 0
    private val composables =
        RecompositionCounts(listOf(ComposableCount("com.example.ui.Greeting", "Ui.kt", 12, 4)), 5_000)

    private fun recording(
        flutter: FlutterRebuildRecorder.Result?,
        compose: () -> RecompositionCounts = { composables },
    ) = RecompositionRecording(
        flutter = { flutter },
        compose = {
            composeRecorded++
            compose()
        },
    )

    private fun widgets(): FlutterRebuildRecorder.Result.Recorded {
        val tracker = RebuildTracker()
        val location = """{"ids":[7],"lines":[40],"columns":[12],"names":["StormTile"]}"""
        val map = """{"file:///app/lib/fixtures/frames.dart":$location}"""
        tracker.seedLocations(JsonParser.parseString(map).asJsonObject)
        tracker.accept(
            FlutterExtensionEvent(
                FlutterExtensionEvent.REBUILT_WIDGETS,
                1,
                null,
                JsonParser.parseString("""{"startTime":16000,"events":[7,4,9,1]}""").asJsonObject,
            ),
        )
        return FlutterRebuildRecorder.Result.Recorded(
            tracker.report(1_000),
            1_000,
            FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF,
            seeded = true,
        )
    }

    @Test
    fun `a Flutter app with a session records widget rebuilds, and Compose is not asked`() {
        val recorded = widgets()

        val outcome = recording(recorded).record()

        assertEquals(RecompositionRecording.Outcome.Widgets(recorded), outcome)
        assertEquals(0, composeRecorded)
    }

    @Test
    fun `a Flutter session that refuses is said, and Compose is not asked`() {
        val outcome = recording(FlutterRebuildRecorder.Result.Refused(FlutterRebuildRecorder.NOT_DEBUG)).record()

        assertEquals(RecompositionRecording.Outcome.Refused(FlutterRebuildRecorder.NOT_DEBUG), outcome)
        assertEquals(0, composeRecorded)
    }

    @Test
    fun `a Compose host embedding Flutter with no Flutter session records Compose`() {
        val outcome = recording(FlutterRebuildRecorder.Result.NoSession(NO_SESSION)).record()

        assertEquals(RecompositionRecording.Outcome.Composables(composables), outcome)
        assertEquals(1, composeRecorded)
    }

    @Test
    fun `a Flutter app Compose cannot record either says both reasons`() {
        val failing = recording(FlutterRebuildRecorder.Result.NoSession(NO_SESSION)) {
            throw RecompositionException("Add androidx.compose.runtime:runtime-tracing.")
        }

        val thrown = assertThrows(RecompositionException::class.java) { failing.record() }

        assertTrue(thrown.message!!.startsWith("Add androidx.compose.runtime:runtime-tracing."), thrown.message)
        assertTrue(thrown.message!!.endsWith(NO_SESSION), thrown.message)
    }

    @Test
    fun `any other app records Compose, and its failure is its own`() {
        assertEquals(RecompositionRecording.Outcome.Composables(composables), recording(null).record())

        val thrown = assertThrows(RecompositionException::class.java) {
            recording(null) { throw RecompositionException("not running") }.record()
        }
        assertEquals("not running", thrown.message)
    }

    @Test
    fun `a widget row names its source to open, and an unnamed one has none`() {
        val rows = widgets().report.top.map(CountRow::of)

        val tile = rows.single { it.name == "StormTile" }
        assertEquals(4L, tile.count)
        assertEquals("lib/fixtures/frames.dart:40:12", tile.location)
        assertEquals("frames.dart", tile.fileName)
        assertEquals("/lib/fixtures/", tile.directory)
        assertEquals(40, tile.line)
        assertTrue(tile.reportedFile.endsWith("/lib/fixtures/frames.dart"), tile.reportedFile)
        val unnamed = rows.single { it.name == "Widget" }
        assertEquals("#9", unnamed.location)
        assertTrue(unnamed.fileName.isEmpty())
        assertTrue(unnamed.reportedFile.isEmpty())
    }

    @Test
    fun `a composable row keeps its package as the directory to look in`() {
        val row = CountRow.of(composables.composables.single())

        assertEquals("Greeting", row.name)
        assertEquals("Ui.kt:12", row.location)
        assertEquals("/com/example/ui/", row.directory)
        assertTrue(row.reportedFile.isEmpty(), "a composable is looked up by name only")
        val files = listOf("/p/lib/other/frames.dart", "/p/lib/fixtures/frames.dart")
        assertEquals(files[1], pickSourceIn(files, "/lib/fixtures/") { it })
        assertFalse(pickSourceIn(files, "") { it } == files[1])
    }

    private companion object {
        const val NO_SESSION = "spock.adb.spock_flutter_sample ships the Flutter engine; recording its widget " +
            "rebuilds needs a live debug session, and it runs without one."
    }
}
