package spock.adb.flutter.vmservice

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A scripted Dart VM Service on [FakeWebSocketServer]. Answers `getVM`, `getIsolate`,
 * `_flutter.listViews`, `streamListen`/`streamCancel` and the two extensions the session uses,
 * from the recorded fixtures in `src/test/resources/vmservice`; records every request; and pushes
 * events on demand. Override any method with [on].
 */
class FakeVmService : AutoCloseable {

    sealed interface Reply {
        data class Result(val result: JsonObject) : Reply
        data class Error(val code: Int, val message: String) : Reply

        /** Answer later with [FakeVmService.reply], or never. */
        object None : Reply
    }

    val server = FakeWebSocketServer(::onRequest)
    val requests = CopyOnWriteArrayList<JsonObject>()
    private val handlers = ConcurrentHashMap<String, (JsonObject) -> Reply>()

    /** Isolate id → `getIsolate` answer. */
    val isolates = ConcurrentHashMap<String, JsonObject>()

    /** The isolates behind views; null when `_flutter.listViews` is not served. */
    @Volatile
    var viewIsolates: List<String>? = listOf(UI_ISOLATE)

    /** Flutter answers its bool extensions with strings. */
    @Volatile
    var structuredErrors = "true"

    /** dart:io answers with a JSON boolean. */
    @Volatile
    var httpLogging = false

    /** Run after `streamListen` has been answered: DDS replays history then. */
    @Volatile
    var afterStreamListen: (String) -> Unit = {}

    val uri: String get() = "ws://127.0.0.1:${server.port}/$TOKEN/ws"

    init {
        isolates[UI_ISOLATE] = fixture("getIsolate-ui-debug.json")
    }

    fun on(method: String, handler: (JsonObject) -> Reply) {
        handlers[method] = handler
    }

    fun reply(id: String, result: JsonObject) {
        server.sendText(response(id).apply { add("result", result) }.toString())
    }

    fun pushEvent(streamId: String, event: JsonObject) {
        server.sendText(notification(streamId, event).toString())
    }

    fun requestsFor(method: String): List<JsonObject> = requests.filter { it.get("method").asString == method }

    /** Waits for [count] requests for [method]; fails the test after [timeoutMs]. */
    fun awaitRequests(method: String, count: Int = 1, timeoutMs: Long = AWAIT_MS): List<JsonObject> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (requestsFor(method).size < count) {
            check(System.nanoTime() < deadline) {
                "No $count × $method in $timeoutMs ms; got ${requests.map { it.get("method") }}"
            }
            Thread.sleep(POLL_MS)
        }
        return requestsFor(method)
    }

    /** A running isolate like the fixture's, with [id] and its own extensions. */
    fun addIsolate(id: String, extensionRpcs: List<String>, pauseKind: String = "Resume"): JsonObject {
        val isolate = fixture("getIsolate-ui-debug.json").apply {
            addProperty("id", id)
            add("extensionRPCs", JsonArray().apply { extensionRpcs.forEach(::add) })
            getAsJsonObject("pauseEvent").addProperty("kind", pauseKind)
        }
        isolates[id] = isolate
        return isolate
    }

    override fun close() = server.close()

    private fun onRequest(text: String) {
        val request = JsonParser.parseString(text).asJsonObject
        requests += request
        val id = request.get("id").asString
        val method = request.get("method").asString
        val params = request.getAsJsonObject("params") ?: JsonObject()
        val reply = handlers[method]?.invoke(params) ?: defaultReply(method, params)
        when (reply) {
            is Reply.Result -> server.sendText(response(id).apply { add("result", reply.result) }.toString())
            is Reply.Error -> server.sendText(
                response(id).apply {
                    add(
                        "error",
                        JsonObject().apply {
                            addProperty("code", reply.code)
                            addProperty("message", reply.message)
                        },
                    )
                }.toString(),
            )
            Reply.None -> Unit
        }
        if (method == "streamListen") afterStreamListen(params.get("streamId").asString)
    }

    private fun defaultReply(method: String, params: JsonObject): Reply = when (method) {
        "getVM" -> Reply.Result(vm())
        "getIsolate" -> isolates[params.get("isolateId").asString]?.let { Reply.Result(it) }
            ?: Reply.Result(
                JsonObject().apply {
                    addProperty("type", "Sentinel")
                    addProperty("kind", "Collected")
                },
            )
        "_flutter.listViews" -> viewIsolates?.let { Reply.Result(views(it)) } ?: notFound()
        "streamListen", "streamCancel" -> Reply.Result(success())
        STRUCTURED_ERRORS -> structuredErrors(params)
        HTTP_LOGGING -> httpLogging(params)
        else -> notFound()
    }

    /** Flutter's bool extension: sets when given `enabled`, and answers with a string either way. */
    private fun structuredErrors(params: JsonObject): Reply {
        params.get("enabled")?.let { structuredErrors = it.asString }
        return Reply.Result(JsonObject().apply { addProperty("enabled", structuredErrors) })
    }

    /** dart:io's: the same contract, answered with a JSON boolean. */
    private fun httpLogging(params: JsonObject): Reply {
        params.get("enabled")?.let { httpLogging = it.asString == "true" }
        return Reply.Result(
            JsonObject().apply {
                addProperty("type", "HttpTimelineLoggingState")
                addProperty("enabled", httpLogging)
            },
        )
    }

    private fun notFound() = Reply.Error(METHOD_NOT_FOUND, "Method not found")

    private fun vm(): JsonObject = fixture("getVM.json").apply {
        add(
            "isolates",
            JsonArray().apply {
                isolates.keys.sorted().forEach { id ->
                    add(isolateRef(id))
                }
            },
        )
    }

    private fun views(ids: List<String>) = JsonObject().apply {
        addProperty("type", "FlutterViewList")
        add(
            "views",
            JsonArray().apply {
                ids.forEach { id ->
                    add(
                        JsonObject().apply {
                            addProperty("type", "FlutterView")
                            add("isolate", isolateRef(id))
                        },
                    )
                }
            },
        )
    }

    companion object {
        const val TOKEN = "SeCrEtToKeN1="
        const val UI_ISOLATE = "isolates/1111"
        const val STRUCTURED_ERRORS = "ext.flutter.inspector.structuredErrors"
        const val HTTP_LOGGING = "ext.dart.io.httpEnableTimelineLogging"
        const val METHOD_NOT_FOUND = -32_601
        const val AWAIT_MS = 5_000L
        private const val POLL_MS = 10L

        fun fixture(name: String): JsonObject =
            JsonParser.parseString(FakeVmService::class.java.getResource("/vmservice/$name")!!.readText()).asJsonObject

        fun success() = JsonObject().apply { addProperty("type", "Success") }

        fun isolateRef(id: String) = JsonObject().apply {
            addProperty("type", "@Isolate")
            addProperty("id", id)
        }

        fun response(id: String) = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
        }

        fun notification(streamId: String, event: JsonObject) = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "streamNotify")
            add(
                "params",
                JsonObject().apply {
                    addProperty("streamId", streamId)
                    add("event", event)
                },
            )
        }

        fun isolateEvent(kind: String, isolateId: String, timestamp: Long, extra: JsonObject.() -> Unit = {}) =
            JsonObject().apply {
                addProperty("type", "Event")
                addProperty("kind", kind)
                add("isolate", isolateRef(isolateId))
                addProperty("timestamp", timestamp)
                extra()
            }

        /** Polls [condition] until it holds; fails after [timeoutMs]. */
        fun eventually(timeoutMs: Long = AWAIT_MS, message: String = "condition", condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for $message" }
                Thread.sleep(POLL_MS)
            }
        }
    }
}
