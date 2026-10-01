package spock.adb.flutter.vmservice

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A JSON-RPC 2.0 client for the Dart VM Service, over the JDK's WebSocket — no new runtime
 * dependency [FR17].
 *
 * Every call blocks the calling thread until its answer or its timeout, so call it from a pooled
 * thread, never the EDT. Answers are matched to calls by request id, so they may arrive in any
 * order. The WebSocket's own thread only reassembles messages and completes calls; events go to
 * [VmServiceListener]s on one dedicated thread, in the order they arrived, so a slow listener
 * delays other events but never an answer.
 */
class VmServiceClient private constructor(
    /** The redacted address, for messages. */
    private val label: String,
    private val defaultTimeoutMs: Long,
) : AutoCloseable {

    private class Pending(val method: String, val future: CompletableFuture<JsonObject>)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val nextId = AtomicLong()
    private val listeners = CopyOnWriteArrayList<VmServiceListener>()
    private val closedReason = AtomicReference<String?>(null)
    private val events: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Spock VM Service events").apply { isDaemon = true }
    }

    @Volatile
    private var socket: WebSocket? = null

    /** The JDK refuses a second text send while one is in flight, so sends are chained. */
    private val sendLock = Any()
    private var lastSend: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    val isOpen: Boolean get() = closedReason.get() == null

    /** Why the connection ended, or null while it is open. */
    val closeReason: String? get() = closedReason.get()

    fun addListener(listener: VmServiceListener) {
        listeners += listener
    }

    fun removeListener(listener: VmServiceListener) {
        listeners -= listener
    }

    /**
     * Calls [method] and returns its `result`.
     *
     * @throws VmServiceRpcException when the VM Service answers with an error.
     * @throws VmServiceTimeoutException when no answer comes within [timeoutMs].
     * @throws VmServiceClosedException when the connection is or becomes closed.
     */
    fun call(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = defaultTimeoutMs): JsonObject {
        val (id, future) = start(method, params)
        val failure: VmServiceException = try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(id)
            VmServiceTimeoutException(method, timeoutMs)
        } catch (e: ExecutionException) {
            e.cause as? VmServiceException ?: VmServiceException("$method failed", e.cause)
        } catch (e: InterruptedException) {
            pending.remove(id)
            Thread.currentThread().interrupt()
            VmServiceClosedException("interrupted while waiting for $method", e)
        }
        throw failure
    }

    /** The call without the wait. The future completes on the WebSocket thread: do not block in it. */
    internal fun callAsync(method: String, params: JsonObject = JsonObject()): CompletableFuture<JsonObject> =
        start(method, params).second

    fun getVM(): JsonObject = call("getVM")

    fun getIsolate(isolateId: String): JsonObject = call("getIsolate", jsonOf("isolateId" to isolateId))

    fun streamListen(streamId: String): JsonObject = call("streamListen", jsonOf("streamId" to streamId))

    fun streamCancel(streamId: String): JsonObject = call("streamCancel", jsonOf("streamId" to streamId))

    /**
     * Calls a service extension (`ext.flutter.…`, `ext.dart.io.…`) on [isolateId]. Extensions
     * receive their arguments as strings, so [params] are strings. An extension runs on the
     * isolate's own event loop: on an isolate paused in the debugger it does not answer, which
     * is why callers check the pause state first and why the timeout matters.
     */
    fun callServiceExtension(
        method: String,
        isolateId: String,
        params: Map<String, String> = emptyMap(),
        timeoutMs: Long = defaultTimeoutMs,
    ): JsonObject {
        val body = jsonOf("isolateId" to isolateId)
        params.forEach { (key, value) -> body.addProperty(key, value) }
        return call(method, body, timeoutMs)
    }

    /** Fails every call still waiting, closes the socket and tells listeners. Safe to repeat. */
    override fun close() {
        shutdown("closed by Spock", sendClose = true)
    }

    private fun start(method: String, params: JsonObject): Pair<String, CompletableFuture<JsonObject>> {
        val id = nextId.incrementAndGet().toString()
        val future = CompletableFuture<JsonObject>()
        pending[id] = Pending(method, future)
        // Checked after registering, so a close that drained the map before this call was added
        // is still seen.
        val reason = closedReason.get()
        val ws = socket
        if (reason != null || ws == null) {
            pending.remove(id)
            future.completeExceptionally(VmServiceClosedException(reason ?: "not connected"))
            return id to future
        }
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        send(ws, request.toString()).whenComplete { _, error ->
            if (error != null) {
                pending.remove(id)?.future?.completeExceptionally(
                    VmServiceClosedException("could not send $method", error),
                )
            }
        }
        return id to future
    }

    private fun send(ws: WebSocket, text: String): CompletableFuture<*> = synchronized(sendLock) {
        val next = lastSend.handle { _, _ -> Unit }.thenCompose { ws.sendText(text, true) }
        lastSend = next
        next
    }

    /** On the WebSocket thread: route one complete message. Kept short — it holds up the reader. */
    private fun onMessage(text: String) {
        val message = try {
            JsonParser.parseString(text) as? JsonObject
        } catch (e: JsonParseException) {
            log.warn("Unreadable VM Service message from $label: ${Redaction.scrub(e.message.orEmpty())}")
            null
        } ?: return
        val method = message.string("method")
        when {
            method == "streamNotify" -> dispatchEvent(message)
            // A request from the VM Service to this client. Spock registers no services.
            method != null -> Unit
            else -> complete(message)
        }
    }

    private fun complete(message: JsonObject) {
        val id = (message.get("id") as? JsonPrimitive)?.asString ?: return
        // Absent when the call already timed out or the connection closed: the answer is dropped.
        val call = pending.remove(id) ?: return
        val error = message.get("error") as? JsonObject
        if (error == null) {
            call.future.complete(message.get("result") as? JsonObject ?: JsonObject())
            return
        }
        val code = (error.get("code") as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt ?: 0
        val text = Redaction.scrub(error.string("message") ?: "error")
        call.future.completeExceptionally(VmServiceRpcException(call.method, code, text, error.get("data")))
    }

    private fun dispatchEvent(message: JsonObject) {
        val params = message.get("params") as? JsonObject ?: return
        val streamId = params.string("streamId") ?: return
        val event = params.get("event") as? JsonObject ?: return
        submit {
            val scrubbed = VmServiceEvent(streamId, Redaction.scrubEvent(event))
            listeners.forEach { deliver(it, scrubbed) }
        }
    }

    // A listener's bug must not cost the other listeners this event, nor stop the event thread.
    // Exception, not RuntimeException: a Kotlin listener can throw a checked one undeclared.
    @Suppress("TooGenericExceptionCaught")
    private fun deliver(listener: VmServiceListener, event: VmServiceEvent) {
        try {
            listener.onEvent(event)
        } catch (e: Exception) {
            log.warn("VM Service listener failed on $event", e)
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            events.execute(task)
        } catch (_: RejectedExecutionException) {
            // Closed: events that arrive while the socket shuts are not delivered.
        }
    }

    private fun shutdown(reason: String, sendClose: Boolean) {
        if (!closedReason.compareAndSet(null, reason)) return
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.future?.completeExceptionally(VmServiceClosedException(reason))
        }
        val ws = socket
        if (sendClose && ws != null) closeSocket(ws)
        submit { listeners.forEach { it.onClosed(reason) } }
        events.shutdown()
    }

    private fun closeSocket(ws: WebSocket) {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: ExecutionException) {
            // Already closed from the other end.
        } catch (_: TimeoutException) {
            // The VM Service did not take the close frame; abort below drops the connection anyway.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            ws.abort()
        }
    }

    private inner class Listener : WebSocket.Listener {
        /** Text frames of one message; the JDK may also split a single frame into several parts. */
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                onMessage(text)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            shutdown("closed by the VM Service ($statusCode)", sendClose = false)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            val detail = Redaction.scrub(error.message ?: error.javaClass.simpleName)
            shutdown("connection lost: $detail", sendClose = false)
        }
    }

    companion object {
        /** Most VM Service calls answer in milliseconds; a longer wait means a stuck isolate. */
        const val DEFAULT_TIMEOUT_MS = 5_000L

        private const val CLOSE_WAIT_MS = 1_000L

        private val log = Logger.getInstance(VmServiceClient::class.java)

        /** Loopback only, so the IDE's HTTP proxy must never see the request. */
        private val http: HttpClient by lazy {
            HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
        }

        /**
         * Opens a connection to [uri]; blocks up to [connectTimeoutMs]. Calls on the returned
         * client time out after [timeoutMs] unless they ask for longer.
         *
         * @throws VmServiceException when the WebSocket cannot be opened. The message is redacted.
         */
        fun connect(
            uri: VmServiceUri,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            connectTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): VmServiceClient {
            val client = VmServiceClient(uri.redacted(), timeoutMs)
            val failure: Throwable = try {
                client.socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .buildAsync(uri.webSocketUri, client.Listener())
                    .get(connectTimeoutMs, TimeUnit.MILLISECONDS)
                return client
            } catch (e: ExecutionException) {
                e.cause ?: e
            } catch (e: TimeoutException) {
                e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                e
            }
            client.shutdown("could not connect", sendClose = false)
            // The cause is not chained: JDK messages may quote the address, token included.
            val detail = failure.message?.takeIf { it.isNotBlank() }?.let { " (${Redaction.scrub(it)})" }.orEmpty()
            throw VmServiceException("Could not connect to ${uri.redacted()}: ${failure.javaClass.simpleName}$detail")
        }

        private fun jsonOf(vararg entries: Pair<String, String>): JsonObject =
            JsonObject().apply { entries.forEach { (key, value) -> addProperty(key, value) } }
    }
}
