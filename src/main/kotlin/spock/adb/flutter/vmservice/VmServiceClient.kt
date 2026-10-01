package spock.adb.flutter.vmservice

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
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
 *
 * A listener slower than the app can fill the event queue. Past [maxQueuedEvents] the oldest
 * `Logging`, `Stdout` and `Stderr` events are dropped and counted in [droppedEventCount]; the
 * events that drive state (`Isolate`, `Debug` and the rest) are never dropped.
 */
class VmServiceClient private constructor(
    /** The redacted address, for messages. */
    private val label: String,
    private val defaultTimeoutMs: Long,
    private val maxQueuedEvents: Int,
    private val maxMessageChars: Int,
) : AutoCloseable {

    private class Pending(val method: String, val future: CompletableFuture<JsonObject>)

    /** An event's delivery; [droppable] when the queue may shed it on overflow. */
    private class EventTask(val droppable: Boolean, private val body: () -> Unit) : Runnable {
        override fun run() = body()
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val nextId = AtomicLong()
    private val listeners = CopyOnWriteArrayList<VmServiceListener>()
    private val closedReason = AtomicReference<String?>(null)
    private val eventQueue = LinkedBlockingQueue<Runnable>()
    private val events = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, eventQueue) { runnable ->
        Thread(runnable, "Spock VM Service events").apply { isDaemon = true }
    }

    /** Orders queueing an event against queueing the close, so no event follows `onClosed`. */
    private val queueLock = Any()
    private val dropped = AtomicLong()

    @Volatile
    private var socket: WebSocket? = null

    /** The JDK refuses a second text send while one is in flight, so sends are chained. */
    private val sendLock = Any()
    private var lastSend: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    val isOpen: Boolean get() = closedReason.get() == null

    /** Why the connection ended, or null while it is open. */
    val closeReason: String? get() = closedReason.get()

    /** `Logging`, `Stdout` and `Stderr` events dropped because the listeners fell behind. */
    val droppedEventCount: Long get() = dropped.get()

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

    /** Calls still waiting for an answer. */
    internal val pendingCount: Int get() = pending.size

    fun getVM(): JsonObject = call("getVM")

    fun getIsolate(isolateId: String): JsonObject = call("getIsolate", jsonOf("isolateId" to isolateId))

    fun streamListen(streamId: String): JsonObject = call("streamListen", jsonOf("streamId" to streamId))

    fun streamCancel(streamId: String): JsonObject = call("streamCancel", jsonOf("streamId" to streamId))

    /**
     * Calls a service extension (`ext.flutter.…`, `ext.dart.io.…`) on [isolateId]. Extensions
     * receive their arguments as strings, so [params] are strings. An extension runs on the
     * isolate's own event loop: on an isolate paused in the debugger it does not answer, which
     * is why callers check the pause state first and why the timeout matters.
     *
     * The two extensions that answer with the VM Service's or DevTools' address
     * ([Redaction.ADDRESS_EXTENSIONS]) have that `value` redacted. Other results are returned as
     * they came: a caller that keeps or shows one scrubs it (P5).
     */
    fun callServiceExtension(
        method: String,
        isolateId: String,
        params: Map<String, String> = emptyMap(),
        timeoutMs: Long = defaultTimeoutMs,
    ): JsonObject {
        val body = jsonOf("isolateId" to isolateId)
        params.forEach { (key, value) -> body.addProperty(key, value) }
        return Redaction.scrubExtensionResult(method, call(method, body, timeoutMs))
    }

    /**
     * Fails every call still waiting with [reason], leaving the connection open: whoever was
     * waiting stops at once, and new calls go through.
     */
    internal fun failPendingCalls(reason: String) = failPending(VmServiceClosedException(reason))

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
        val data = error.get("data")?.let(Redaction::scrubJson)
        call.future.completeExceptionally(VmServiceRpcException(call.method, code, text, data))
    }

    private fun dispatchEvent(message: JsonObject) {
        val params = message.get("params") as? JsonObject ?: return
        val streamId = params.string("streamId") ?: return
        val event = params.get("event") as? JsonObject ?: return
        queueEvent(EventTask(streamId in DROPPABLE_STREAMS) { deliverEvent(streamId, event) })
    }

    // The event is scrubbed here, on the event thread, inside the guard: whatever the scrub or a
    // listener throws costs this event, never the thread or the events after it. Exception, not
    // RuntimeException: a Kotlin listener can throw a checked one undeclared.
    @Suppress("TooGenericExceptionCaught")
    private fun deliverEvent(streamId: String, raw: JsonObject) {
        val event = try {
            VmServiceEvent(streamId, Redaction.scrubEvent(raw))
        } catch (e: Exception) {
            log.warn("Could not redact a $streamId event from $label; it is dropped", e)
            return
        }
        listeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                log.warn("VM Service listener failed on $event", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deliverClosed(reason: String) {
        listeners.forEach { listener ->
            try {
                listener.onClosed(reason)
            } catch (e: Exception) {
                log.warn("VM Service listener failed on close", e)
            }
        }
    }

    /** Queues [task] unless the connection is closed, shedding a droppable event on overflow. */
    private fun queueEvent(task: EventTask) {
        synchronized(queueLock) {
            if (closedReason.get() != null) return
            if (eventQueue.size >= maxQueuedEvents && !makeRoom(task)) return
            execute(task)
        }
    }

    /** Drops the oldest droppable event; false when [incoming] is the one to drop. */
    private fun makeRoom(incoming: EventTask): Boolean {
        val oldest = eventQueue.firstOrNull { it is EventTask && it.droppable }
        val room = when {
            oldest != null && eventQueue.remove(oldest) -> true
            incoming.droppable -> false
            // Only state-driving events are queued: the queue goes over its bound rather than lose one.
            else -> return true
        }
        if (dropped.incrementAndGet() == 1L) {
            log.warn("VM Service listeners for $label fell $maxQueuedEvents events behind; dropping logs")
        }
        return room
    }

    private fun execute(task: Runnable) {
        try {
            events.execute(task)
        } catch (_: RejectedExecutionException) {
            // The event thread is gone: the connection closed.
        }
    }

    private fun failPending(failure: VmServiceException) = pending.keys.toList().forEach { id ->
        pending.remove(id)?.future?.completeExceptionally(failure)
    }

    private fun shutdown(reason: String, sendClose: Boolean) {
        if (!closedReason.compareAndSet(null, reason)) return
        failPending(VmServiceClosedException(reason))
        val ws = socket
        if (sendClose && ws != null) closeSocket(ws)
        if (!sendClose) ws?.abort()
        // The close is the last task: queueEvent checks closedReason under the same lock.
        synchronized(queueLock) {
            execute { deliverClosed(reason) }
            events.shutdown()
        }
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
        private var buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            // A handshake that finished after connect gave up: nobody holds this socket.
            if (!isOpen) {
                webSocket.abort()
                return
            }
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            try {
                if (buffer.length + data.length > maxMessageChars) {
                    buffer = StringBuilder()
                    shutdown("a VM Service message was larger than $maxMessageChars characters", sendClose = true)
                    return null
                }
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    // A large message leaves a large buffer behind; most messages are small.
                    if (buffer.capacity() > KEPT_BUFFER_CHARS) buffer = StringBuilder() else buffer.setLength(0)
                    onMessage(text)
                }
            } finally {
                // Whatever the message did, the reader goes on to the next one.
                if (isOpen) webSocket.request(1)
            }
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

        /** As many as DDS replays to a new subscriber, so one replay never overflows the queue. */
        const val MAX_QUEUED_EVENTS = 10_000

        /** No VM Service answer Spock asks for comes near this; a message past it is a fault. */
        const val MAX_MESSAGE_CHARS = 64 * 1024 * 1024

        private const val CLOSE_WAIT_MS = 1_000L
        private const val KEPT_BUFFER_CHARS = 1024 * 1024

        /** Redirects a VM may answer an upgrade with: 301, 302, 303, 307, 308. */
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /** Streams whose events only inform: on overflow they go first. */
        private val DROPPABLE_STREAMS = setOf("Logging", "Stdout", "Stderr")

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
            maxQueuedEvents: Int = MAX_QUEUED_EVENTS,
            maxMessageChars: Int = MAX_MESSAGE_CHARS,
        ): VmServiceClient {
            val client = VmServiceClient(uri.redacted(), timeoutMs, maxQueuedEvents, maxMessageChars)
            val handshake = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .buildAsync(uri.webSocketUri, client.Listener())
            val failure: Throwable = try {
                client.socket = handshake.get(connectTimeoutMs, TimeUnit.MILLISECONDS)
                return client
            } catch (e: ExecutionException) {
                e.cause ?: e
            } catch (e: TimeoutException) {
                e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                e
            }
            // A handshake that completes after this gave up would leave a socket nobody holds —
            // on a direct address one that keeps DDS from starting (spike S10).
            handshake.thenAccept { it.abort() }
            handshake.cancel(true)
            client.shutdown("could not connect", sendClose = false)
            redirectOf(uri, failure)?.let { throw VmServiceRedirectException(it, uri.redacted()) }
            // The cause is not chained: JDK messages may quote the address, token included.
            val detail = failure.message?.takeIf { it.isNotBlank() }?.let { " (${Redaction.scrub(it)})" }.orEmpty()
            throw VmServiceException("Could not connect to ${uri.redacted()}: ${failure.javaClass.simpleName}$detail")
        }

        /**
         * Where a `3xx` answer to the upgrade points. Once DDS owns the VM, the VM's own address
         * answers every upgrade with a `302` to DDS (Dart's `vmservice_server.dart`). The
         * `Location` carries DDS's token, so it is read here and never put in a message.
         *
         * @throws VmServiceException when it points somewhere Spock does not connect to.
         */
        private fun redirectOf(uri: VmServiceUri, failure: Throwable): VmServiceUri? {
            val handshake = generateSequence(failure) { it.cause }.filterIsInstance<WebSocketHandshakeException>()
                .firstOrNull() ?: return null
            val response = handshake.response ?: return null
            if (response.statusCode() !in REDIRECT_STATUSES) return null
            val location = response.headers().firstValue("Location").orElse(null) ?: return null
            val resolved = try {
                uri.webSocketUri.resolve(location).toString()
            } catch (_: IllegalArgumentException) {
                location
            }
            return VmServiceUri.parseOrNull(resolved) ?: throw VmServiceException(
                "${uri.redacted()} hands its clients to an address that is not on this machine's loopback; " +
                    "Spock does not follow it",
            )
        }

        private fun jsonOf(vararg entries: Pair<String, String>): JsonObject =
            JsonObject().apply { entries.forEach { (key, value) -> addProperty(key, value) } }
    }
}
