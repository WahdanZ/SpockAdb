package spock.adb.uitree

import spock.adb.device.ops.RecompositionException
import spock.adb.flutter.FlutterRebuildRecorder
import spock.adb.flutter.analysis.RebuildTracker
import spock.adb.flutter.analysis.shortSourcePath

/**
 * One recording window, for the UI Inspector's Recompositions tab and
 * `android_get_recomposition_counts` alike: Flutter widget rebuilds for an app with a live Flutter
 * session ([flutter]), Compose's recompositions ([compose]) for any other. A Compose host that
 * embeds a Flutter module ships the Flutter engine too; with no Flutter session it is recorded as
 * Compose, and the Flutter reason is told only if Compose cannot record it either.
 *
 * Blocking for the window: from a pooled thread.
 *
 * @param flutter the rebuild window, or null for an app that is not Flutter.
 * @param compose Compose's recording; throws [RecompositionException] or [UiCaptureException].
 */
internal class RecompositionRecording(
    private val flutter: () -> FlutterRebuildRecorder.Result?,
    private val compose: () -> RecompositionCounts,
) {

    sealed interface Outcome {
        data class Composables(val counts: RecompositionCounts) : Outcome
        data class Widgets(val recorded: FlutterRebuildRecorder.Result.Recorded) : Outcome

        /** A Flutter session that would not record: nothing was written. [reason] is fit to show. */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * @throws RecompositionException when Compose cannot record the app, with why a Flutter app's
     *   rebuilds could not be recorded either.
     * @throws UiCaptureException when the device is lost or a command times out.
     */
    fun record(): Outcome = when (val rebuilds = flutter()) {
        is FlutterRebuildRecorder.Result.Recorded -> Outcome.Widgets(rebuilds)
        is FlutterRebuildRecorder.Result.Refused -> Outcome.Refused(rebuilds.reason)
        is FlutterRebuildRecorder.Result.NoSession -> Outcome.Composables(composeOr(rebuilds.reason))
        null -> Outcome.Composables(compose())
    }

    private fun composeOr(noSession: String): RecompositionCounts = try {
        compose()
    } catch (e: RecompositionException) {
        throw RecompositionException("${e.message.orEmpty()}\n\n$noSession", e)
    }

    companion object {
        /** What became of the shared tracking flag, in words, for a Flutter window. */
        fun trackingWords(tracking: FlutterRebuildRecorder.Tracking): String = when (tracking) {
            FlutterRebuildRecorder.Tracking.SWITCHED_ON_AND_OFF ->
                "Rebuild tracking: Spock switched it on for the recording and off again."
            FlutterRebuildRecorder.Tracking.ALREADY_ON ->
                "Rebuild tracking: it was on already (the IDE's rebuild counts or DevTools), so Spock left it on."
            FlutterRebuildRecorder.Tracking.LEFT_ON ->
                "Rebuild tracking: Spock switched it on and could not switch it off again (the connection to the " +
                    "app was lost, the app was paused in the debugger, or it did not answer). After a lost " +
                    "connection, Spock's next session switches it off only if the same debugger session (DDS) is " +
                    "still running and shows nothing else wrote it; after `flutter run` itself ended, Spock cannot " +
                    "tell, and it stays on until the app restarts (R) or DevTools or the IDE's rebuild counts switch " +
                    "it off."
            FlutterRebuildRecorder.Tracking.ISOLATE_GONE ->
                "Rebuild tracking: the app restarted during the recording, and the tracking Spock switched on " +
                    "went with the old isolate; counts stop at the restart."
            FlutterRebuildRecorder.Tracking.CHANGED_BY_OTHERS ->
                "Rebuild tracking: something else (the IDE's rebuild counts or DevTools) switched it during the " +
                    "recording, so Spock left it as that set it; counts may cover only part of the recording."
            FlutterRebuildRecorder.Tracking.UNCONFIRMED ->
                "Rebuild tracking: the app did not answer Spock's switch to turn it on, nor say afterwards whether " +
                    "it was on, so Spock cannot tell whether it changed the flag and left it as it is; it may stay " +
                    "on until the app restarts."
        }

        const val UNSEEDED = "Spock could not read the inspector's widget locations, so widgets the app " +
            "described before the recording appear by number."
        const val SESSION_ENDED = "The Flutter session ended during the recording, so it stopped early."
    }
}

/**
 * A row of the Recompositions tab: how often one composable composed, or one Flutter widget
 * location was built, and where its source is — [fileName] under [directory], at [line].
 */
internal data class CountRow(
    val count: Long,
    val name: String,
    val location: String,
    val fileName: String,
    /** A path fragment the right file of that name sits under: `/com/example/ui/`, `/lib/fixtures/`. */
    val directory: String,
    val line: Int,
    /** A Flutter widget's file as Flutter reported it, `file:///…/lib/fixtures/frames.dart`; empty for a composable. */
    val reportedFile: String = "",
) {
    companion object {
        fun of(composable: ComposableCount) = CountRow(
            count = composable.count.toLong(),
            name = composable.simpleName,
            location = composable.location,
            fileName = composable.file,
            directory = "/" + composable.packageName.replace('.', '/') + "/",
            line = composable.line,
        )

        /** A widget location named only by its id has no source to open: its [fileName] is empty. */
        fun of(widget: RebuildTracker.WidgetRebuilds): CountRow {
            val at = widget.location
            val path = at?.file?.let(::shortSourcePath).orEmpty()
            return CountRow(
                count = widget.rebuilds,
                name = at?.name ?: "Widget",
                location = at?.let { "$path:${it.line}:${it.column}" } ?: "#${widget.id}",
                fileName = path.substringAfterLast('/'),
                directory = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { "/$it/" }.orEmpty(),
                line = at?.line ?: 0,
                reportedFile = at?.file.orEmpty(),
            )
        }
    }
}
