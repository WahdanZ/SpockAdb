package spock.adb.assistant

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Anthropic's Messages API over `java.net.http`.
 *
 * No SDK: this plugin ships with one dependency and must keep `verifyPlugin` green on five
 * IDEs, so a transitive HTTP and JSON stack is not a trade worth making for one endpoint that
 * is a POST and a stream of server-sent events.
 */
class AnthropicClient(
    private val apiKey: () -> String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: HttpClient = defaultHttpClient(),
) : LlmClient {

    override fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): LlmResponse {
        val key = apiKey()
        if (key.isBlank()) {
            throw LlmException("No Anthropic API key is set. Add one in Settings > Tools > Spock ADB.")
        }

        val request = HttpRequest.newBuilder(URI.create("$baseUrl$MESSAGES_PATH"))
            .header("content-type", "application/json")
            .header("x-api-key", key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body(system, messages, tools).toString()))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofLines())
        } catch (e: java.io.IOException) {
            throw LlmException("Could not reach $baseUrl: ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmException("The request was interrupted.", e)
        }

        if (response.statusCode() !in SUCCESS) {
            // Verbatim, including the provider's own error body: a paraphrase drops the part
            // that says which field was wrong.
            val detail = runCatching { response.body().limit(ERROR_LINES).toList().joinToString("\n") }
                .getOrDefault("")
            throw LlmException("Anthropic returned HTTP ${response.statusCode()}. $detail".trim())
        }

        return response.body().use { lines ->
            AnthropicStream.parse(lines.iterator(), onTextDelta, isCancelled)
        }
    }

    private fun body(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>) =
        JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", MAX_TOKENS)
            addProperty("stream", true)
            if (system.isNotBlank()) addProperty("system", system)
            // `thinking` is deliberately absent. It is on by default on this model family, and
            // the older `budget_tokens` form is rejected outright — sending either would be a
            // 400 rather than a tuning knob.
            if (tools.isNotEmpty()) {
                add(
                    "tools",
                    JsonArray().apply {
                        tools.forEach { spec ->
                            add(
                                JsonObject().apply {
                                    addProperty("name", spec.name)
                                    addProperty("description", spec.description)
                                    add("input_schema", spec.inputSchema)
                                },
                            )
                        }
                    },
                )
            }
            add("messages", JsonArray().apply { messages.forEach { add(wire(it)) } })
        }

    /** One conversation turn as Anthropic content blocks. */
    private fun wire(message: LlmMessage): JsonObject = JsonObject().apply {
        addProperty("role", if (message.role == LlmMessage.Role.ASSISTANT) "assistant" else "user")
        add(
            "content",
            JsonArray().apply {
                message.text?.takeIf { it.isNotBlank() }?.let { text ->
                    add(
                        JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", text)
                        },
                    )
                }
                message.toolCalls.forEach { call ->
                    add(
                        JsonObject().apply {
                            addProperty("type", "tool_use")
                            addProperty("id", call.id)
                            addProperty("name", call.name)
                            add("input", call.arguments)
                        },
                    )
                }
                // Every result for a turn goes in this one message. Splitting them across
                // several teaches the model to stop asking for parallel calls.
                message.toolResults.forEach { result ->
                    add(
                        JsonObject().apply {
                            addProperty("type", "tool_result")
                            addProperty("tool_use_id", result.id)
                            addProperty("content", result.content)
                            // A failed tool is reported as a failure, never dropped: the model
                            // cannot route around an error it was not told about.
                            if (result.isError) addProperty("is_error", true)
                        },
                    )
                }
            },
        )
    }

    companion object {
        /** The current default. Never date-suffixed — the plain id is the whole id. */
        const val DEFAULT_MODEL = "claude-opus-5"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MESSAGES_PATH = "/v1/messages"

        /** Generous because the response is streamed; a long turn is not a stuck one. */
        private const val MAX_TOKENS = 64_000
        private val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val SUCCESS = 200..299
        private const val ERROR_LINES = 20L
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)

        private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build()
    }
}

/**
 * The server-sent event stream, turned into one [LlmResponse].
 *
 * Split from the HTTP call so it can be driven from a canned stream: the frame handling —
 * accumulating a tool call's arguments across `input_json_delta` fragments, and stopping
 * cleanly part-way through — is the part worth testing, and it needs no network to test.
 */
internal object AnthropicStream {

    fun parse(
        lines: Iterator<String>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): LlmResponse {
        val state = State()
        while (lines.hasNext() && !isCancelled()) {
            val event = eventOf(lines.next()) ?: continue
            handle(event, state, onTextDelta)
        }
        return state.build()
    }

    /** Null for keepalives, event-name lines and anything that is not a JSON data frame. */
    private fun eventOf(line: String): JsonObject? {
        if (!line.startsWith(DATA_PREFIX)) return null
        val payload = line.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE) return null
        return runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
    }

    private fun handle(event: JsonObject, state: State, onTextDelta: (String) -> Unit) {
        when (event.get("type")?.asString) {
            "content_block_start" -> state.startBlock(event)
            "content_block_delta" -> state.delta(event, onTextDelta)
            "message_delta" -> state.stopReason = event.getAsJsonObject("delta")
                ?.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString ?: state.stopReason
            "error" -> throw LlmException(
                event.getAsJsonObject("error")?.get("message")?.asString
                    ?: "The provider reported an error mid-stream.",
            )
        }
    }

    private class State {
        private val text = StringBuilder()
        private val toolCalls = mutableListOf<PartialCall>()
        var stopReason: String? = null

        fun startBlock(event: JsonObject) {
            val block = event.getAsJsonObject("content_block") ?: return
            if (block.get("type")?.asString != "tool_use") return
            toolCalls += PartialCall(
                index = event.get("index")?.asInt ?: toolCalls.size,
                id = block.get("id")?.asString.orEmpty(),
                name = block.get("name")?.asString.orEmpty(),
            )
        }

        fun delta(event: JsonObject, onTextDelta: (String) -> Unit) {
            val delta = event.getAsJsonObject("delta") ?: return
            when (delta.get("type")?.asString) {
                "text_delta" -> delta.get("text")?.asString?.let {
                    text.append(it)
                    onTextDelta(it)
                }
                // Tool arguments arrive as JSON fragments that are not valid JSON on their
                // own, so they are concatenated and parsed once at the end.
                "input_json_delta" -> delta.get("partial_json")?.asString?.let { fragment ->
                    val index = event.get("index")?.asInt
                    toolCalls.firstOrNull { it.index == index }?.arguments?.append(fragment)
                }
            }
        }

        fun build() = LlmResponse(
            text = text.toString(),
            toolCalls = toolCalls.mapNotNull { it.build() },
            stopReason = stopReason,
        )
    }

    private class PartialCall(val index: Int, val id: String, val name: String) {
        val arguments = StringBuilder()

        /**
         * Null when the call never completed — a stream cut short by cancellation leaves
         * arguments that do not parse, and half a tool call must not be run.
         */
        fun build(): LlmToolCall? {
            if (id.isBlank() || name.isBlank()) return null
            val parsed = if (arguments.isEmpty()) {
                JsonObject()
            } else {
                runCatching { JsonParser.parseString(arguments.toString()).asJsonObject }.getOrNull()
            }
            return parsed?.let { LlmToolCall(id, name, it) }
        }
    }

    private const val DATA_PREFIX = "data:"
    private const val DONE = "[DONE]"
}
