package spock.adb.mcp.stdio

import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP's stdio transport: newline-delimited JSON-RPC over a byte stream.
 *
 * It owns framing, concurrency and cancellation, and **nothing else**. Every message is
 * handed verbatim to [handle] — which is [spock.adb.mcp.McpProtocol.handle] — so `initialize`,
 * tool discovery, tool calls, unknown methods and malformed JSON are all answered by the same
 * implementation that serves the HTTP transport. There is deliberately no second protocol
 * here to drift from the first.
 *
 * It takes streams rather than touching `System.in` / `System.out`, so the same class serves
 * a socket connection inside the IDE (see [McpBridgeServer]) and a real process stdio pair,
 * and can be tested over a pipe.
 *
 * Framing is the MCP stdio spec's: one JSON message per line, UTF-8, no embedded newlines.
 *
 * **Nothing but protocol messages may reach [output].** Diagnostics go to [diagnostics], which
 * in the IDE is the log and in the launcher process is stderr. A stray `println` on this
 * stream corrupts the session for the client, which is why this class never prints.
 */
class McpStdioServer(
    private val handle: (String) -> String?,
    private val diagnostics: (String, Throwable?) -> Unit = { _, _ -> },
) {

    private val running = AtomicBoolean(false)

    /**
     * Requests being executed right now, by JSON-RPC id, so `notifications/cancelled` has
     * something to act on. Entries are removed as each request finishes.
     */
    private val inFlight = ConcurrentHashMap<String, Thread>()

    /** Ids cancelled by the client. A cancelled request's response is never written. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val workers = Executors.newFixedThreadPool(WORKERS) { runnable ->
        Thread(runnable, "SpockAdb-MCP-stdio").apply { isDaemon = true }
    }

    /**
     * Reads messages from [input] until end of stream, answering on [output].
     *
     * Blocks the calling thread. Returns when the peer closes its end (the client exited),
     * when [shutdown] is called, or when the stream errors — all of which mean the session is
     * over and are normal, not failures.
     */
    fun serve(input: InputStream, output: OutputStream) = serve(
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)),
        OutputStreamWriter(output, StandardCharsets.UTF_8),
    )

    /**
     * Serves an already-wrapped reader and writer.
     *
     * [McpBridgeServer] needs this: it reads the token line off the connection before the
     * session starts, and a second [BufferedReader] over the same socket would silently take
     * the bytes the first one had already buffered.
     */
    fun serve(reader: BufferedReader, writer: Writer) {
        running.set(true)
        try {
            while (running.get()) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                dispatch(line, writer)
            }
        } catch (e: java.io.IOException) {
            // A client that exits mid-read closes the pipe. That is how a stdio session ends,
            // not something to report as a failure.
            diagnostics("stdio session ended: ${e.message}", null)
        } finally {
            shutdown()
        }
    }

    /**
     * Stops serving and releases the worker pool. Safe to call more than once.
     *
     * Deliberately unconditional rather than guarded on [running]: a server that was shut down
     * before it ever served, or that has already stopped, must still release its threads.
     * Guarding this on the running flag leaked the pool in exactly those two cases.
     *
     * This does **not** unblock a [serve] call sitting in `readLine`. Nothing can, short of
     * closing the stream — which is how a stdio session ends anyway, and what
     * [McpBridgeServer] does to each live connection when it stops.
     */
    fun shutdown() {
        running.set(false)
        // Interrupt anything still running: a tool call blocked on a device must not keep the
        // IDE's executor alive after the client has gone.
        inFlight.values.forEach { it.interrupt() }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS) }
    }

    private fun dispatch(line: String, writer: Writer) {
        // Peek only. A message that will not parse is still forwarded verbatim, so the parse
        // error comes back from the protocol implementation rather than from a second, subtly
        // different one here.
        val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
        val method = runCatching { message?.get("method")?.asString }.getOrNull()

        if (method == CANCEL_NOTIFICATION) {
            cancel(message)
            return
        }

        val id = message?.get("id")?.takeIf { !it.isJsonNull }?.toString()

        // Every request runs on a worker so reading continues while a tool executes: a
        // cancellation that arrived behind a slow call would otherwise never be read.
        workers.execute {
            id?.let { inFlight[it] = Thread.currentThread() }
            try {
                val response = handleSafely(line)
                // A cancelled request gets no response, per the MCP spec: the client has
                // already stopped waiting for one.
                if (response != null && (id == null || !cancelled.contains(id))) {
                    write(writer, response)
                }
            } finally {
                id?.let {
                    inFlight.remove(it)
                    cancelled.remove(it)
                }
                // Clear the interrupt so the pooled thread is reusable after a cancellation.
                Thread.interrupted()
            }
        }
    }

    // The protocol boundary must not be able to kill the session: an unexpected exception
    // becomes an error response, never a dropped connection the client waits on forever.
    @Suppress("TooGenericExceptionCaught")
    private fun handleSafely(line: String): String? = try {
        handle(line)
    } catch (e: Exception) {
        diagnostics("MCP request failed", e)
        null
    }

    private fun cancel(message: com.google.gson.JsonObject?) {
        val id = runCatching {
            message?.getAsJsonObject("params")?.get("requestId")?.takeIf { !it.isJsonNull }?.toString()
        }.getOrNull() ?: return

        cancelled += id
        inFlight[id]?.interrupt()
        diagnostics("Cancelled in-flight request $id", null)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun write(writer: Writer, response: String) {
        // One message per line is the frame. A response containing a raw newline would be read
        // by the client as two broken messages; Gson escapes newlines inside strings, so this
        // only guards against a future change in how responses are produced.
        val framed = response.replace("\n", "").replace("\r", "")
        synchronized(writer) {
            try {
                writer.write(framed)
                writer.write("\n")
                writer.flush()
            } catch (e: Exception) {
                diagnostics("Could not write MCP response", e)
                running.set(false)
            }
        }
    }

    companion object {
        const val CANCEL_NOTIFICATION = "notifications/cancelled"

        private const val WORKERS = 4
        private const val SHUTDOWN_GRACE_SECONDS = 2L
    }
}
