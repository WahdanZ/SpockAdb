package spock.adb.flutter

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.FlutterRebuildRecorder.Companion.TRACK_REBUILDS
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.vmservice.ExtensionResults
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.string
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a session did about rebuild tracking an earlier session's recording left on. */
enum class RebuildFlagRestore {
    /** Still on; this debugger session replayed Spock's switch-on, and no write of it after: switched off. */
    SWITCHED_OFF,

    /**
     * Still on, and Spock cannot prove it is its own: this debugger session does not hold Spock's
     * switch-on — a new `flutter run` or `flutter attach` starts a new one, with no history of
     * what was written before it — so whatever wrote the flag since is out of sight. Left as it is.
     */
    LEFT_UNPROVEN,

    /** Still on, and something else wrote it after Spock's switch-on: left as that set it. */
    LEFT_TO_OTHERS,
}

/**
 * Rebuild tracking a recording left on because its connection was lost while the app ran on. As
 * [HttpOwners] does for HTTP recording, the window remembers in [RebuildFlagOwners] that Spock
 * switched `trackRebuildDirtyWidgets` on in (serial, pid, isolate), with the device time of its
 * switch-on's announcement; the next session on that process over DDS switches it off, and only
 * when Spock can prove it is still its own:
 * - the isolate still runs: a hot restart took the flag with the old one, and the record goes;
 * - the flag is still on;
 * - this session's replay holds Spock's own announcement — `Flutter.ServiceExtensionStateChanged`,
 *   on, at exactly that device time: DDS replays the original event — and no write of the flag
 *   came after it, in the order the events arrived. Order, not timestamps: the device's clock can
 *   step back, and two writes can share a millisecond.
 *
 * Only a DDS that outlived Spock's dropped connection replays Spock's announcement. When
 * `flutter run` died, its DDS died with it, and the next one (`flutter attach`) starts with no
 * history: what the IDE or DevTools wrote in between cannot be seen, so the flag is left on and
 * the session says so ([RebuildFlagRestore.LEFT_UNPROVEN]).
 *
 * Any session — DDS or direct — that sees a write of the flag after Spock's forgets the record:
 * the flag is someone else's from then on. Listens from before the session connects, for the
 * replay. The calls run on the session's thread, once the replay is delivered; a paused isolate
 * or a call that fails leaves the record for a later session.
 */
internal class LeftOnRebuildFlag(
    private val session: FlutterSession,
    /** Off the event thread: the session's own. */
    private val runLater: (Runnable) -> Unit,
    /** Into the session's snapshot ([FlutterSessionSnapshot.rebuildFlagRestore]), for the Timeline and status. */
    private val report: (RebuildFlagRestore) -> Unit,
) : FlutterSessionListener {

    /** What one isolate's announced writes of the flag say, in the order they arrived. */
    private class Seen {
        /** The device time of Spock's owed switch-on, once its announcement was replayed here. */
        var spocksAt: Long? = null

        /** Writes after it — or live, which are after it in any case: someone else's. */
        var after = 0
    }

    /** Per isolate; guarded by this. */
    private val seen = HashMap<String, Seen>()

    /** What this session forgot on seeing someone else's write, still to be said; guarded by this. */
    private val takenOver = ArrayList<RebuildFlagOwners.Owed>()

    /** Counted down once an owed restore is settled, or given up on; null when none was owed. */
    @Volatile
    private var settling: CountDownLatch? = null

    /** On the event thread, in the order the app's writes arrived: replay first, then live. */
    override fun onEvent(event: FlutterEvent) {
        val read = FlutterExtensionEvent.from(event) ?: return
        if (read.kind != STATE_CHANGED || read.data.string("extension") != TRACK_REBUILDS) return
        val isolateId = read.isolateId ?: return
        val owed = owedOn(isolateId)
        // The flag is someone else's from now on, whichever session would settle it; this one says so.
        if (note(isolateId, read, event.history, owed) && owed != null) session.rebuildOwners?.forget(owed)
    }

    /** Puts one write of the flag in [isolateId]'s order; true when [owed] is someone else's now. */
    private fun note(
        isolateId: String,
        read: FlutterExtensionEvent,
        history: Boolean,
        owed: RebuildFlagOwners.Owed?,
    ): Boolean = synchronized(this) {
        val on = seen.getOrPut(isolateId) { Seen() }
        when {
            on.spocksAt == null && isSpocks(read, history, owed) -> on.spocksAt = read.timestampMs
            // A live write comes after Spock's, which was in an earlier connection.
            on.spocksAt != null || !history -> on.after++
            // Replayed before Spock's, as far as the order tells yet: earlier, or out of sight.
            else -> Unit
        }
        val taken = owed != null && on.after > 0
        if (taken && takenOver.none { it === owed }) takenOver += checkNotNull(owed)
        taken
    }

    /**
     * On connect over DDS, once the streams are listened to: when Spock owes this process a
     * switch-off, queues it behind the replay. Nothing is called when nothing is owed.
     */
    fun restoreSoon(connected: VmServiceClient) {
        val owners = session.rebuildOwners ?: return
        val serial = session.ownerSerial ?: return
        val pid = session.snapshot.vmPid ?: return
        // A replayed write after Spock's may have taken the record already: it is still this session's to tell.
        val owed = synchronized(this) { (owners.ofProcess(serial, pid) + takenOver).distinct() }
        if (owed.isEmpty()) return
        val settled = CountDownLatch(1).also { settling = it }
        // DDS sends a stream's replay before it answers streamListen: it is all queued by now.
        connected.afterQueuedEvents {
            runLater(
                Runnable {
                    try {
                        owed.forEach { restore(connected, owners, it) }
                    } finally {
                        settled.countDown()
                    }
                },
            )
        }
    }

    /**
     * Waits up to [timeoutMs] for an owed switch-off to settle, so a recording opened right after
     * connecting does not take Spock's earlier flag for the IDE's.
     */
    fun awaitSettled(timeoutMs: Long) {
        try {
            settling?.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Spock's owed switch-on, replayed: on, at exactly the device time the window saw it announced. */
    private fun isSpocks(read: FlutterExtensionEvent, history: Boolean, owed: RebuildFlagOwners.Owed?): Boolean =
        owed != null && history && read.timestampMs == owed.lastAnnouncedAt &&
            ExtensionResults.bool(read.data, "value") == true

    private fun owedOn(isolateId: String): RebuildFlagOwners.Owed? {
        val serial = session.ownerSerial ?: return null
        val pid = session.snapshot.vmPid ?: return null
        return session.rebuildOwners?.ofProcess(serial, pid)?.firstOrNull { it.isolateId == isolateId }
    }

    private fun restore(connected: VmServiceClient, owners: RebuildFlagOwners, owed: RebuildFlagOwners.Owed) {
        if (session.client !== connected) return
        try {
            if (settle(connected, owed)) owners.forget(owed)
        } catch (e: VmServiceException) {
            log.info("Could not switch off the rebuild tracking Spock left on: ${Redaction.scrub(e.message.orEmpty())}")
        }
    }

    /** Whether [owed] is settled — switched off, left on, or gone — rather than still owed. */
    private fun settle(connected: VmServiceClient, owed: RebuildFlagOwners.Owed): Boolean {
        val params = JsonObject().apply { addProperty("isolateId", owed.isolateId) }
        val isolate = FlutterIsolate.from(connected.call("getIsolate", params, CALL_MS))
        val on = when {
            // A hot restart: Flutter reset the flag with the isolate.
            isolate == null -> return true
            // It would not answer.
            isolate.paused -> return false
            else -> readBool(connected, TRACK_REBUILDS, owed.isolateId) ?: return false
        }
        // Decided right before the write, so a write announced meanwhile counts.
        val left = if (on) leftOn(owed) else null
        when {
            !on -> Unit
            left == null -> switchOff(connected, owed)
            else -> report(left)
        }
        return true
    }

    /** Why the flag stays on, or null when it is provably Spock's to switch off. */
    private fun leftOn(owed: RebuildFlagOwners.Owed): RebuildFlagRestore? = synchronized(this) {
        val on = seen[owed.isolateId]
        when {
            on == null || on.spocksAt != owed.lastAnnouncedAt -> RebuildFlagRestore.LEFT_UNPROVEN
            on.after > 0 -> RebuildFlagRestore.LEFT_TO_OTHERS
            else -> null
        }
    }

    private fun switchOff(connected: VmServiceClient, owed: RebuildFlagOwners.Owed) {
        connected.callServiceExtension(TRACK_REBUILDS, owed.isolateId, mapOf("enabled" to "false"), CALL_MS)
        report(RebuildFlagRestore.SWITCHED_OFF)
        log.info("Spock switched off the rebuild tracking its recording left on in pid ${owed.pid}")
    }

    companion object {
        /** A recording waits this long for an owed switch-off: its three calls, and a little. */
        const val SETTLE_WAIT_MS = 3 * 2_000L + 500L

        private const val CALL_MS = 2_000L
        private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"
        private val log = Logger.getInstance(LeftOnRebuildFlag::class.java)
    }
}

/**
 * Where a rebuild recording left tracking on, its connection lost, across the sessions of one
 * project: keyed by (serial, pid, isolate) as [HttpOwners] is, with the device time of the
 * announcement of Spock's switch-on — what a later session looks for in its replay. Bounded; a new
 * process drops the old one's.
 */
internal class RebuildFlagOwners(private val capacity: Int = DEFAULT_CAPACITY) {

    class Owed(val serial: String, val pid: Int, val isolateId: String, val lastAnnouncedAt: Long) {
        val key: String get() = HttpOwners.key(serial, pid, isolateId)
    }

    private val owed = LinkedHashMap<String, Owed>()

    @Synchronized
    fun record(entry: Owed) {
        owed.remove(entry.key)
        owed[entry.key] = entry
        while (owed.size > capacity) owed.remove(owed.keys.first())
    }

    @Synchronized
    fun ofProcess(serial: String, pid: Int): List<Owed> = owed.values.filter { it.serial == serial && it.pid == pid }

    /** Forgets [entry], not a newer record of the same isolate that replaced it. */
    @Synchronized
    fun forget(entry: Owed) {
        owed.remove(entry.key, entry)
    }

    /** Drops what was owed in [serial]'s processes other than [pids]: those isolates are gone. */
    @Synchronized
    fun retainPids(serial: String, pids: Set<Long>) {
        owed.values.removeAll { it.serial == serial && it.pid.toLong() !in pids }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
