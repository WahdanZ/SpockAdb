package spock.adb.mcp

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [McpHistoryStore], written off the calling thread.
 *
 * The thread recording a call is the one answering it: an agent waiting on a result should not
 * also be waiting on a disk write, still less on the occasional compaction that rewrites the
 * whole file. Each task drains everything queued, so a burst of calls costs one append rather
 * than one per call — which is the shape MCP traffic actually has.
 *
 * A failure to persist never propagates. The call has already happened; losing the record of it
 * is worth a log line, not a failed tool call.
 */
class McpHistoryWriter(private val store: McpHistoryStore) {

    private val pending = ConcurrentLinkedQueue<McpCall>()

    /** One thread, so appends cannot interleave; daemon, so a pending write cannot hold the IDE open. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spock-adb-mcp-history").apply { isDaemon = true }
    }

    fun record(call: McpCall) {
        pending.add(call)
        // Rejected once [shutdown] has run, which happens only as the IDE closes. The call is
        // already in the in-memory history and, when destructive, in the IDE log.
        runCatching { worker.execute(::drain) }
    }

    fun clear() {
        pending.clear()
        runCatching { worker.execute { store.clear() } }
    }

    /**
     * Writes what is still queued, then stops accepting work.
     *
     * Without the flush, the calls made in the last moments before the IDE closes would be
     * exactly the ones missing from the record of what an agent did. Bounded, because a stuck
     * disk must not hold up shutdown.
     */
    fun shutdown() {
        runCatching { worker.submit(::drain).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        worker.shutdownNow()
    }

    private fun drain() {
        val batch = mutableListOf<McpCall>()
        while (true) {
            batch += pending.poll() ?: break
        }
        // Nothing queued: an earlier task drained it. Returning before touching the store keeps
        // an IDE that never records a call from creating the file at all.
        if (batch.isEmpty()) return

        if (store.append(batch) && store.needsCompaction()) store.compact()
    }

    private companion object {
        const val FLUSH_TIMEOUT_SECONDS = 2L
    }
}
