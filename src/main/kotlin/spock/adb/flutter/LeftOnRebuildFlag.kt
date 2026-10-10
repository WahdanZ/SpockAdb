package spock.adb.flutter

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import spock.adb.flutter.FlutterRebuildRecorder.Companion.TRACK_REBUILDS
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceClient
import spock.adb.flutter.vmservice.VmServiceException
import spock.adb.flutter.vmservice.string
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a session did about rebuild tracking an earlier session's recording left on. */
enum class RebuildFlagRestore {
    /** Still on, and nothing but Spock was seen writing it: switched off. */
    SWITCHED_OFF,

    /** Still on, and something else wrote it since, or Spock could not see every write: left as it is. */
    LEFT_AS_IS,
}

/**
 * Rebuild tracking a recording left on because its connection was lost — `flutter run` quit, its
 * DDS with it — while the app ran on. As [HttpOwners] does for HTTP recording, the window
 * remembers in [RebuildFlagOwners] that Spock switched `trackRebuildDirtyWidgets` on in (serial,
 * pid, isolate), with the device time of the last write of it the app announced; the next session
 * on that process over DDS switches it off, and only when Spock can prove it is still its own:
 * - the isolate still runs: a hot restart took the flag with the old one, and the record goes;
 * - the flag is still on;
 * - no write of it newer than Spock's was announced. DDS replays the `Extension` stream to a new
 *   subscriber, so what the IDE or DevTools wrote through that DDS before Spock connected is seen
 *   here; a replay as long as DDS keeps may have lost the oldest, and then nothing is proven.
 *
 * Listens from before the session connects, for the replay. The calls run on the session's
 * thread, once the replay is delivered; a paused isolate or a call that fails leaves the record
 * for a later session.
 */
internal class LeftOnRebuildFlag(
    private val session: FlutterSession,
    /** Off the event thread: the session's own. */
    private val runLater: (Runnable) -> Unit,
    /** Into the session's snapshot ([FlutterSessionSnapshot.rebuildFlagRestore]), for the Timeline and status. */
    private val report: (RebuildFlagRestore) -> Unit,
) : FlutterSessionListener {

    /** Per isolate, the device time of the newest write of the flag announced on this session, replays included. */
    private val announced = ConcurrentHashMap<String, Long>()

    /** `Extension` events DDS replayed: as many as it keeps means the oldest may be gone. */
    private val replayed = AtomicInteger()

    /** Counted down once an owed restore is settled, or given up on; null when none was owed. */
    @Volatile
    private var settling: CountDownLatch? = null

    override fun onEvent(event: FlutterEvent) {
        if (event.history && event.streamId == EXTENSION_STREAM) replayed.incrementAndGet()
        val read = FlutterExtensionEvent.from(event) ?: return
        if (read.kind != STATE_CHANGED || read.data.string("extension") != TRACK_REBUILDS) return
        read.isolateId?.let { announced.merge(it, read.timestampMs, ::maxOf) }
    }

    /**
     * On connect over DDS, once the streams are listened to: when Spock owes this process a
     * switch-off, queues it behind the replay. Nothing is called when nothing is owed.
     */
    fun restoreSoon(connected: VmServiceClient) {
        val owners = session.rebuildOwners ?: return
        val serial = session.ownerSerial ?: return
        val pid = session.snapshot.vmPid ?: return
        val owed = owners.ofProcess(serial, pid)
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

    private fun restore(connected: VmServiceClient, owners: RebuildFlagOwners, owed: RebuildFlagOwners.Owed) {
        if (session.client !== connected) return
        try {
            if (settle(connected, owed)) owners.forget(owed)
        } catch (e: VmServiceException) {
            log.info("Could not switch off the rebuild tracking Spock left on: ${Redaction.scrub(e.message.orEmpty())}")
        }
    }

    /** Whether [owed] is settled — switched off, left to others, or gone — rather than still owed. */
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
        when {
            !on -> Unit
            !provenSpocks(owed) -> report(RebuildFlagRestore.LEFT_AS_IS)
            else -> {
                connected.callServiceExtension(TRACK_REBUILDS, owed.isolateId, mapOf("enabled" to "false"), CALL_MS)
                report(RebuildFlagRestore.SWITCHED_OFF)
                log.info("Spock switched off the rebuild tracking its recording left on in pid ${owed.pid}")
            }
        }
        return true
    }

    /** No write of the flag newer than the last one the window saw, and a replay that reaches back far enough. */
    private fun provenSpocks(owed: RebuildFlagOwners.Owed): Boolean {
        val newest = announced[owed.isolateId]
        val since = owed.lastAnnouncedAt
        val othersWrote = newest != null && (since == null || newest > since)
        return !othersWrote && replayed.get() < EventHistory.DEFAULT_CAPACITY
    }

    companion object {
        /** A recording waits this long for an owed switch-off: its three calls, and a little. */
        const val SETTLE_WAIT_MS = 3 * 2_000L + 500L

        private const val CALL_MS = 2_000L
        private const val EXTENSION_STREAM = "Extension"
        private const val STATE_CHANGED = "Flutter.ServiceExtensionStateChanged"
        private val log = Logger.getInstance(LeftOnRebuildFlag::class.java)
    }
}

/**
 * Where a rebuild recording left tracking on, its connection lost, across the sessions of one
 * project: keyed by (serial, pid, isolate) as [HttpOwners] is, with the device time of the last
 * write of the flag the window saw announced — null when it saw none, and then any write seen
 * later is someone else's. Bounded; a new process drops the old one's.
 */
internal class RebuildFlagOwners(private val capacity: Int = DEFAULT_CAPACITY) {

    class Owed(val serial: String, val pid: Int, val isolateId: String, val lastAnnouncedAt: Long?) {
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

    @Synchronized
    fun forget(entry: Owed) {
        owed.remove(entry.key)
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
