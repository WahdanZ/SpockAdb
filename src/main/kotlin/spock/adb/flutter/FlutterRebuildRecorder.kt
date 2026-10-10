package spock.adb.flutter

import com.google.gson.JsonObject
import spock.adb.CancellationSignal
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.analysis.RebuildTracker
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.NoUiIsolateException
import spock.adb.flutter.vmservice.ReadOnlyConnectionException
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceClosedException
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.VmServicePausedException
import spock.adb.flutter.vmservice.VmServiceRpcException
import spock.adb.flutter.vmservice.string
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One window of Flutter rebuild counts on a live session: what `android_get_recomposition_counts`
 * records for a Flutter app instead of Compose's recompositions (plan P5b).
 *
 * A write to the app, so only on explicit request — never from Diagnose — and only where Spock
 * may write: a DDS session ([FlutterSession.readOnly] is false) in a debug build, the only one with
 * the inspector. `ext.flutter.inspector.trackRebuildDirtyWidgets` is shared with the IDE's
 * rebuild counts [FR10], so it is read first and switched on only when off; and switched off
 * afterwards only when Spock switched it on, only on the isolate it was switched on in (a hot
 * restart takes the flag with the old isolate), and only when nobody else wrote it meanwhile: the
 * app announces every write with `Flutter.ServiceExtensionStateChanged`, so one beyond Spock's own
 * is the IDE's or DevTools', and theirs to undo. Should Spock close the session first, the close
 * switches it off ([FlutterSession.CloseRestore]); should the connection be lost, the next session
 * on the process switches it off, when it can prove the flag is still Spock's ([LeftOnRebuildFlag]).
 *
 * Switching tracking on makes Flutter rebuild the whole tree once (a reassemble), and announce
 * the switch after that frame; rebuilds count from that announcement, so the forced frame is not
 * the app's. Meanwhile `Flutter.RebuiltWidgets` events of the UI isolate go to a [RebuildTracker],
 * seeded with `widgetLocationIdMap`: each location is sent once per isolate, so a window opened
 * after the IDE's counts sees bare ids otherwise.
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
        /** [windowMs] is how long it recorded, shorter than asked when the caller cancelled or the app went. */
        data class Recorded(
            val report: RebuildTracker.Report,
            val windowMs: Long,
            val tracking: Tracking,
            /** False when `widgetLocationIdMap` could not be read: unknown ids are listed by number. */
            val seeded: Boolean,
            /** The session ended during the window, which ended it early. */
            val sessionEnded: Boolean = false,
        ) : Result

        /** Nothing was written. [reason] is fit to show, scrubbed. */
        data class Refused(val reason: String) : Result

        /**
         * There is no live session to record on, so nothing was asked of the app; [reason] says
         * why, scrubbed. The caller may record something else: a Compose host that embeds a
         * Flutter module is a Compose app too.
         */
        data class NoSession(val reason: String) : Result
    }

    /** What happened to the shared tracking flag. */
    enum class Tracking {
        /** Spock switched it on for the window and off again. */
        SWITCHED_ON_AND_OFF,

        /** It was on already — the IDE's rebuild counts, DevTools — and Spock left it as it was. */
        ALREADY_ON,

        /**
         * Spock switched it on and could not switch it off: the connection was lost, the isolate
         * was paused in the debugger, or the app did not answer. A lost connection's is switched
         * off by the next session on the process ([LeftOnRebuildFlag]).
         */
        LEFT_ON,

        /** The app restarted meanwhile: the isolate Spock switched it on in is gone, and the flag with it. */
        ISOLATE_GONE,

        /** Something else wrote it during the window, so Spock left it as that set it. */
        CHANGED_BY_OTHERS,

        /**
         * Spock's switch-on got no answer, and the app would not say afterwards whether tracking
         * was on: Spock cannot tell whether it was its write, so it left the flag as it is.
         */
        UNCONFIRMED,
    }

    /**
     * Records for [windowMs], or until [cancelled], the session ends or the app restarts. [limit]
     * widgets are listed.
     */
    fun record(windowMs: Long, cancelled: CancellationSignal, limit: Int = RebuildTracker.DEFAULT_LIMIT): Result {
        val isolateId = session.snapshot.uiIsolateId
        // Claims the session last, only when nothing else refuses: the finally below releases it.
        val refused = refusal() ?: NO_ISOLATE.takeIf { isolateId == null }
            ?: ALREADY_RECORDING.takeIf { !recording.add(session) }
        if (refused != null || isolateId == null) return Result.Refused(refused ?: NO_ISOLATE)
        try {
            return Window(isolateId).record(windowMs, cancelled, limit)
        } finally {
            recording.remove(session)
        }
    }

    private fun refusal(): String? = when {
        session.readOnly -> READ_ONLY
        session.buildMode != FlutterBuild.DEBUG -> NOT_DEBUG
        else -> null
    }

    /**
     * One window on [isolateId]: its events, the writes of the flag the app announced, and
     * switching the flag back off — once, by whichever comes first, the window's end or Spock's
     * close of the session.
     */
    private inner class Window(private val isolateId: String) : FlutterSessionListener, FlutterSession.CloseRestore {
        val tracker = RebuildTracker()

        /** Writes of the flag the app announced since the listener was added: Spock's, and anyone's. */
        private val writes = AtomicInteger()

        /** The device time of the newest of [writes]: Spock's switch-on's, which a later session looks for. */
        @Volatile
        private var lastAnnouncedAt: Long? = null

        /** Where the window runs, read while connected: what a lost connection leaves owed is keyed by it. */
        private val serial = session.ownerSerial
        private val pid = session.snapshot.vmPid

        /** Set at the app's announcement that tracking is on: the frame it forced came before. */
        private val counting = AtomicBoolean()
        private val switchedOn = CountDownLatch(1)

        @Volatile
        var sessionEnded = false
            private set

        @Volatile
        private var isolateExited = false

        val ended: Boolean get() = sessionEnded || isolateExited

        /** Spock's switch-on is known to be Spock's: only then is anything switched off. */
        @Volatile
        private var confirmed = false

        private val restoreClaimed = AtomicBoolean()
        private val closeRestored = CountDownLatch(1)

        @Volatile
        private var closeOutcome: Tracking? = null

        fun record(windowMs: Long, cancelled: CancellationSignal, limit: Int): Result {
            // Listening before the flag is read, so a write by anyone from then on is seen.
            session.addListener(this)
            // Right after a connect, the flag may be one an earlier window left on: settled first.
            session.leftOnRebuilds.awaitSettled(LeftOnRebuildFlag.SETTLE_WAIT_MS)
            var switched = false
            var tracking: Tracking? = null
            try {
                val wasOn = when (val read = readFlag()) {
                    is FlagRead.Known -> read.on
                    is FlagRead.Failed -> return Result.Refused(read.reason)
                }
                if (wasOn) {
                    countFromNow()
                } else {
                    // Before the write, so a close from the moment it is confirmed switches it off.
                    session.closeRestores.add(this)
                    when (val write = switchOn()) {
                        is Write.Refused -> return Result.Refused(write.reason)
                        Write.Unconfirmed -> tracking = Tracking.UNCONFIRMED
                        Write.Confirmed -> {
                            switched = true
                            confirmed = true
                        }
                    }
                    // The app announces the switch after the frame it forced; without an announcement, from now.
                    awaitAnnouncement()
                }
                val started = clock()
                val seeded = seed(tracker, isolateId)
                waitFor(windowMs, cancelled)
                val elapsed = clock() - started
                val report = tracker.report(elapsed, limit)
                val outcome = tracking ?: end(switched)
                tracking = outcome
                return Result.Recorded(report, elapsed, outcome, seeded, sessionEnded)
            } finally {
                // Whatever ended the window, what Spock switched on is switched off.
                if (switched && tracking == null) end(switched = true)
                session.closeRestores.remove(this)
                session.removeListener(this)
            }
        }

        override fun onEvent(event: FlutterEvent) {
            if (event.history || event.isolateId != isolateId) return
            if (event.kind == ISOLATE_EXIT) {
                isolateExited = true
                return
            }
            val read = FlutterExtensionEvent.from(event) ?: return
            when (read.kind) {
                FlutterExtensionEvent.REBUILT_WIDGETS -> if (counting.get()) tracker.accept(read)
                STATE_CHANGED -> if (read.data.string("extension") == TRACK_REBUILDS) {
                    writes.incrementAndGet()
                    lastAnnouncedAt = maxOf(lastAnnouncedAt ?: read.timestampMs, read.timestampMs)
                    if (ExtensionResults.bool(read.data, "value") == true) {
                        counting.set(true)
                        switchedOn.countDown()
                    }
                }
            }
        }

        override fun onStateChanged(state: SessionState) {
            if (state !is SessionState.Disconnected) return
            sessionEnded = true
            if (state.reason != FlutterSession.CLOSED_BY_SPOCK) leaveOwed()
        }

        /**
         * The connection was lost: no switch-off can go through it, and none runs on close. A
         * switch-on known to be Spock's, whose announcement was the only write seen, is left for
         * the next session on the process to switch off.
         */
        private fun leaveOwed() {
            if (!confirmed || writes.get() != 1 || restoreClaimed.get()) return
            val owners = session.rebuildOwners ?: return
            val announced = lastAnnouncedAt ?: return
            owners.record(RebuildFlagOwners.Owed(serial ?: return, pid ?: return, isolateId, announced))
        }

        /** Spock's close, before the socket closes: switches off what the window switched on, unless it did. */
        override fun restore(connected: VmServiceClient) {
            if (!confirmed || !restoreClaimed.compareAndSet(false, true)) return
            try {
                closeOutcome = switchOff(connected)
            } finally {
                closeRestored.countDown()
            }
        }

        private fun countFromNow() {
            counting.set(true)
            switchedOn.countDown()
        }

        private fun awaitAnnouncement() {
            val announced = try {
                switchedOn.await(ANNOUNCE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!announced) countFromNow()
        }

        /**
         * What became of the flag; switches it off when Spock switched it on, unless the close did.
         * A cancelled call (an interrupt) still switches it off.
         */
        private fun end(switched: Boolean): Tracking = uninterrupted { decide(switched) }

        private fun decide(switched: Boolean): Tracking {
            if (!switched) return if (writes.get() > 0) Tracking.CHANGED_BY_OTHERS else Tracking.ALREADY_ON
            val connected = session.client?.takeIf { !session.readOnly }
            if (connected != null && restoreClaimed.compareAndSet(false, true)) return switchOff(connected)
            // A lost connection leaves nothing to write through, and runs no close.
            if (connected == null && !closedBySpock()) return Tracking.LEFT_ON
            // Spock's close has it, or is about to.
            val closed = try {
                closeRestored.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            return closeOutcome.takeIf { closed } ?: Tracking.LEFT_ON
        }

        private fun closedBySpock(): Boolean =
            (session.state as? SessionState.Disconnected)?.reason == FlutterSession.CLOSED_BY_SPOCK

        /**
         * Through [connected], to [isolateId] by its id rather than "the UI isolate": a selection
         * between isolates says nothing of whether this one still runs. The flag is left to whoever
         * else wrote it; a gone isolate took it along; a paused one would not answer.
         */
        private fun switchOff(connected: VmServiceClient): Tracking = try {
            val params = JsonObject().apply { addProperty("isolateId", isolateId) }
            val isolate = FlutterIsolate.from(connected.call("getIsolate", params, RESTORE_MS))
            when {
                isolate == null -> Tracking.ISOLATE_GONE
                // Spock's own write announced itself once; any other announcement is someone else's.
                writes.get() > 1 -> Tracking.CHANGED_BY_OTHERS
                isolate.paused -> Tracking.LEFT_ON
                else -> {
                    connected.callServiceExtension(TRACK_REBUILDS, isolateId, mapOf("enabled" to "false"), RESTORE_MS)
                    Tracking.SWITCHED_ON_AND_OFF
                }
            }
        } catch (_: VmServiceException) {
            Tracking.LEFT_ON
        }

        private fun waitFor(windowMs: Long, cancelled: CancellationSignal) {
            val end = clock() + windowMs
            while (!cancelled.isCancelled() && !ended) {
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

    /** How Spock's switch-on went. */
    private sealed interface Write {
        /** The app answered it, or a read afterwards found tracking on: Spock's to switch off. */
        data object Confirmed : Write

        /** Not written, or written and not applied: nothing to switch off. */
        data class Refused(val reason: String) : Write

        /** No answer, and no read since: it may or may not have applied, so it is not Spock's to undo. */
        data object Unconfirmed : Write
    }

    /**
     * Switches tracking on. One that never left — a paused isolate, a closed session, none
     * selected — is refused; one the app never answered is read back, since the flag is shared
     * with the IDE: only a write known to have switched it is Spock's to switch off.
     */
    private fun switchOn(): Write = try {
        session.callUiExtension(TRACK_REBUILDS, mapOf("enabled" to "true"), CALL_MS)
        Write.Confirmed
    } catch (e: VmServiceRpcException) {
        Write.Refused(scrub("The app refused to switch rebuild tracking on: ${e.message}"))
    } catch (e: VmServicePausedException) {
        notSwitched(e)
    } catch (e: VmServiceClosedException) {
        // A cancelled call stops waiting; the write it sent still lands.
        if (Thread.currentThread().isInterrupted) readBack() else notSwitched(e)
    } catch (e: NoUiIsolateException) {
        notSwitched(e)
    } catch (e: ReadOnlyConnectionException) {
        notSwitched(e)
    } catch (_: VmServiceException) {
        readBack()
    }

    /** After a switch-on with no answer: it was off when read, so on now is the write landing. */
    private fun readBack(): Write = when (uninterrupted { readFlag() }) {
        FlagRead.Known(true) -> Write.Confirmed
        FlagRead.Known(false) -> Write.Refused(UNANSWERED_STILL_OFF)
        else -> Write.Unconfirmed
    }

    private fun notSwitched(e: VmServiceException): Write =
        Write.Refused(scrub("Spock could not switch rebuild tracking on, so it changed nothing: ${e.message}"))

    /** `widgetLocationIdMap` answers `{result: {file: {ids, lines, columns, names}}}`. */
    private fun seed(tracker: RebuildTracker, isolateId: String): Boolean = try {
        val answer = session.callUiExtension(LOCATION_MAP, emptyMap(), CALL_MS)
        tracker.seedLocations(answer.get("result") as? JsonObject ?: answer, isolateId)
        true
    } catch (_: VmServiceException) {
        false
    }

    private fun scrub(text: String): String = Redaction.scrub(text)

    companion object {
        const val TRACK_REBUILDS = "ext.flutter.inspector.trackRebuildDirtyWidgets"
        const val LOCATION_MAP = "ext.flutter.inspector.widgetLocationIdMap"

        private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"
        private const val ISOLATE_EXIT = "IsolateExit"

        private const val CALL_MS = 3_000L
        private const val POLL_MS = 200L

        /** The app announces the switch right before it answers; this covers the events trailing the answer. */
        private const val ANNOUNCE_WAIT_MS = 1_000L

        /** Each call of a switch-off, as quick as closing should be. */
        private const val RESTORE_MS = 2_000L

        /** A close's switch-off: its two calls, and a little. */
        private const val CLOSE_WAIT_MS = 2 * RESTORE_MS + 500L

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
        private const val UNANSWERED_STILL_OFF =
            "The app did not answer Spock's switch to turn rebuild tracking on, and it is still off: nothing changed."
    }
}

/** [block] with the thread's interrupt cleared, and set again after: a switch-off must not be cut short by a cancel. */
private inline fun <T> uninterrupted(block: () -> T): T {
    val interrupted = Thread.interrupted()
    try {
        return block()
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
