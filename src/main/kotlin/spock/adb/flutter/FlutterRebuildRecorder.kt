package spock.adb.flutter

import com.google.gson.JsonObject
import spock.adb.CancellationSignal
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.RebuildTracker
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServiceRpcException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * One window of Flutter rebuild counts on a live session: what `android_get_recomposition_counts`
 * records for a Flutter app instead of Compose's recompositions (plan P5b).
 *
 * A write to the app, so only on explicit request — never from Diagnose — and only where Spock
 * may write: a DDS session ([FlutterSession.readOnly] is false) in a debug build, the only one with
 * the inspector. `ext.flutter.inspector.trackRebuildDirtyWidgets` is shared with the IDE's
 * rebuild counts [FR10], so it is read first and switched on only when off; and switched off
 * afterwards only when Spock switched it on, and only on the isolate it was switched on in (a hot
 * restart takes the flag with the old isolate). Meanwhile `Flutter.RebuiltWidgets` events of the
 * UI isolate go to a [RebuildTracker], seeded with `widgetLocationIdMap`: each location is sent
 * once per isolate, so a window opened after the IDE's counts sees bare ids otherwise.
 *
 * One window per session at a time: a second would take the first's flag for already on, and
 * leave it on. Blocking for the window: from a pooled thread.
 */
class FlutterRebuildRecorder(
    private val session: FlutterSession,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Result {
        /** [windowMs] is how long it recorded, shorter than asked when the caller cancelled. */
        data class Recorded(
            val report: RebuildTracker.Report,
            val windowMs: Long,
            val tracking: Tracking,
            /** False when `widgetLocationIdMap` could not be read: unknown ids are listed by number. */
            val seeded: Boolean,
        ) : Result

        /** Nothing was written. [reason] is fit to show, scrubbed. */
        data class Refused(val reason: String) : Result
    }

    /** What happened to the shared tracking flag. */
    enum class Tracking {
        /** Spock switched it on for the window and off again. */
        SWITCHED_ON_AND_OFF,

        /** It was on already — the IDE's rebuild counts, DevTools — and Spock left it as it was. */
        ALREADY_ON,

        /** Spock switched it on and could not switch it off: the session ended, or the app would not answer. */
        LEFT_ON,

        /** The app restarted meanwhile: the isolate Spock switched it on in is gone, and the flag with it. */
        ISOLATE_GONE,
    }

    /**
     * Records for [windowMs], or until [cancelled]. [limit] widgets are listed.
     */
    fun record(windowMs: Long, cancelled: CancellationSignal, limit: Int = RebuildTracker.DEFAULT_LIMIT): Result {
        val isolateId = session.snapshot.uiIsolateId
        // Claims the session last, only when nothing else refuses: the finally below releases it.
        val refused = refusal() ?: NO_ISOLATE.takeIf { isolateId == null }
            ?: ALREADY_RECORDING.takeIf { !recording.add(session) }
        if (refused != null || isolateId == null) return Result.Refused(refused ?: NO_ISOLATE)
        try {
            val wasOn = when (val read = readFlag()) {
                is FlagRead.Known -> read.on
                is FlagRead.Failed -> return Result.Refused(read.reason)
            }
            return window(isolateId, wasOn, windowMs, cancelled, limit)
        } finally {
            recording.remove(session)
        }
    }

    private fun window(
        isolateId: String,
        wasOn: Boolean,
        windowMs: Long,
        cancelled: CancellationSignal,
        limit: Int,
    ): Result {
        val tracker = RebuildTracker()
        val listener = object : FlutterSessionListener {
            override fun onEvent(event: FlutterEvent) {
                val read = FlutterExtensionEvent.from(event) ?: return
                if (read.kind == FlutterExtensionEvent.REBUILT_WIDGETS && read.isolateId == isolateId) {
                    tracker.accept(read)
                }
            }
        }
        // Listening before switching on, so the first frame's event is not missed.
        session.addListener(listener)
        // A write that times out may still apply: it counts as Spock's, and switching off is harmless.
        val refused = if (wasOn) null else switch()
        if (refused != null) {
            session.removeListener(listener)
            return Result.Refused(refused)
        }
        var tracking = Tracking.ALREADY_ON
        val (report, elapsed, seeded) = try {
            val seeded = seed(tracker, isolateId)
            val started = clock()
            waitFor(windowMs, cancelled)
            val elapsed = clock() - started
            Triple(tracker.report(elapsed, limit), elapsed, seeded)
        } finally {
            session.removeListener(listener)
            if (!wasOn) tracking = restore(isolateId)
        }
        return Result.Recorded(report, elapsed, tracking, seeded)
    }

    private fun refusal(): String? = when {
        session.readOnly -> READ_ONLY
        session.buildMode != FlutterBuild.DEBUG -> NOT_DEBUG
        else -> null
    }

    private sealed interface FlagRead {
        data class Known(val on: Boolean) : FlagRead
        data class Failed(val reason: String) : FlagRead
    }

    private fun readFlag(): FlagRead = try {
        ExtensionResults.bool(session.callUiExtension(TRACK_REBUILDS, emptyMap(), CALL_MS))
            ?.let { FlagRead.Known(it) }
            ?: FlagRead.Failed(UNREADABLE_FLAG)
    } catch (e: VmServiceException) {
        FlagRead.Failed(scrub("Could not read whether rebuild tracking is on, so Spock changed nothing: ${e.message}"))
    }

    /** Switches tracking on: null when written, or why the app refused; one with no answer counts as written. */
    private fun switch(): String? = try {
        session.callUiExtension(TRACK_REBUILDS, mapOf("enabled" to "true"), CALL_MS)
        null
    } catch (e: VmServiceRpcException) {
        scrub("The app refused to switch rebuild tracking on: ${e.message}")
    } catch (_: VmServiceException) {
        null
    }

    /** A lost session cannot say what became of the isolate: the app may still run with tracking on. */
    private fun restore(isolateId: String): Tracking = when (session.snapshot.uiIsolateId) {
        null -> Tracking.LEFT_ON
        isolateId -> try {
            session.callUiExtension(TRACK_REBUILDS, mapOf("enabled" to "false"), CALL_MS)
            Tracking.SWITCHED_ON_AND_OFF
        } catch (_: VmServiceException) {
            Tracking.LEFT_ON
        }
        // Another UI isolate: a hot restart, which took the flag with the old one.
        else -> Tracking.ISOLATE_GONE
    }

    /** `widgetLocationIdMap` answers `{result: {file: {ids, lines, columns, names}}}`. */
    private fun seed(tracker: RebuildTracker, isolateId: String): Boolean = try {
        val answer = session.callUiExtension(LOCATION_MAP, emptyMap(), CALL_MS)
        tracker.seedLocations(answer.get("result") as? JsonObject ?: answer, isolateId)
        true
    } catch (_: VmServiceException) {
        false
    }

    private fun waitFor(windowMs: Long, cancelled: CancellationSignal) {
        val end = clock() + windowMs
        while (!cancelled.isCancelled()) {
            val left = end - clock()
            if (left <= 0) return
            try {
                sleep(minOf(left, POLL_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun scrub(text: String): String = Redaction.scrub(text)

    companion object {
        const val TRACK_REBUILDS = "ext.flutter.inspector.trackRebuildDirtyWidgets"
        const val LOCATION_MAP = "ext.flutter.inspector.widgetLocationIdMap"

        private const val CALL_MS = 3_000L
        private const val POLL_MS = 200L

        /** Sessions with a window open, by identity. */
        private val recording: MutableSet<FlutterSession> = Collections.newSetFromMap(ConcurrentHashMap())

        const val READ_ONLY = "Spock is connected to this app's VM directly, without DDS, and only watches it: " +
            "recording rebuilds switches a flag in the app. Run the app with `flutter run` or `flutter attach`."
        const val NOT_DEBUG = "Rebuild tracking is a debug-build feature of the Flutter inspector; this app is " +
            "not a debug build, or has not registered its inspector yet."
        const val NO_ISOLATE = "The app has no Flutter UI isolate selected yet; try again in a moment."
        const val ALREADY_RECORDING = "A rebuild recording is already running on this app; wait for it to end."
        private const val UNREADABLE_FLAG =
            "The app did not say whether rebuild tracking is on, so Spock changed nothing."
    }
}
