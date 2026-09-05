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
 * Any endpoint that speaks OpenAI's `/chat/completions`.
 *
 * One class rather than several because the base URL is the only thing that differs between
 * OpenAI itself, a local Ollama, and a corporate proxy — and a plugin that made the developer
 * pick from a list of three would be wrong the day a fourth appeared.
 */
class OpenAiCompatibleClient(
    private val apiKey: () -> String,
    private val model: String,
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT).build(),
) : LlmClient {

    override fun send(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): LlmResponse {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + COMPLETIONS_PATH))
            .header("content-type", "application/json")
            .apply { apiKey().takeIf { it.isNotBlank() }?.let { header("authorization", "Bearer $it") } }
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
            val detail = runCatching { response.body().limit(ERROR_LINES).toList().joinToString("\n") }.getOrDefault("")
            throw LlmException("$baseUrl returned HTTP ${response.statusCode()}. $detail".trim())
        }

        return response.body().use { OpenAiStream.parse(it.iterator(), onTextDelta, isCancelled) }
    }

    private fun body(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>) = JsonObject().apply {
        addProperty("model", model)
        addProperty("stream", true)
        if (tools.isNotEmpty()) {
            add(
                "tools",
                JsonArray().apply {
                    tools.forEach { spec ->
                        add(
                            JsonObject().apply {
                                addProperty("type", "function")
                                add(
                                    "function",
                                    JsonObject().apply {
                                        addProperty("name", spec.name)
                                        addProperty("description", spec.description)
                                        add("parameters", spec.inputSchema)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        add(
            "messages",
            JsonArray().apply {
                if (system.isNotBlank()) {
                    add(
                        JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", system)
                        },
                    )
                }
                messages.forEach { message -> wire(message).forEach { add(it) } }
            },
        )
    }

    /**
     * One neutral turn as one or more OpenAI messages.
     *
     * Not one-to-one: OpenAI carries each tool result as its own `role: "tool"` message, where
     * Anthropic carries them all as blocks inside a single user turn. The neutral [LlmMessage]
     * is the Anthropic shape, so a turn holding several results fans out here.
     */
    private fun wire(message: LlmMessage): List<JsonObject> = when {
        message.toolResults.isNotEmpty() -> message.toolResults.map { result ->
            JsonObject().apply {
                addProperty("role", "tool")
                addProperty("tool_call_id", result.id)
                // No is_error field exists here, so the failure has to be said in words or the
                // model cannot tell a refusal from a result.
                addProperty("content", if (result.isError) "ERROR: ${result.content}" else result.content)
            }
        }
        message.role == LlmMessage.Role.ASSISTANT -> listOf(
            JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", message.text.orEmpty())
                if (message.toolCalls.isNotEmpty()) {
                    add(
                        "tool_calls",
                        JsonArray().apply {
                            message.toolCalls.forEach { call ->
                                add(
                                    JsonObject().apply {
                                        addProperty("id", call.id)
                                        addProperty("type", "function")
                                        add(
                                            "function",
                                            JsonObject().apply {
                                                addProperty("name", call.name)
                                                addProperty("arguments", call.arguments.toString())
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
        else -> listOf(
            JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", message.text.orEmpty())
            },
        )
    }

    private companion object {
        const val COMPLETIONS_PATH = "/chat/completions"
        const val ERROR_LINES = 20L
        val SUCCESS = 200..299
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}

/** The `chat.completion.chunk` stream, turned into one [LlmResponse]. */
internal object OpenAiStream {

    fun parse(
        lines: Iterator<String>,
        onTextDelta: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): LlmResponse {
        val state = State()
        while (lines.hasNext() && !isCancelled()) {
            val choice = choiceOf(lines.next()) ?: continue
            state.consume(choice, onTextDelta)
        }
        return state.build()
    }

    private fun choiceOf(line: String): JsonObject? {
        if (!line.startsWith(DATA_PREFIX)) return null
        val payload = line.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE) return null
        return runCatching {
            JsonParser.parseString(payload).asJsonObject
                .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
        }.getOrNull()
    }

    private class State {
        private val text = StringBuilder()
        private val calls = linkedMapOf<Int, PartialCall>()
        private var finishReason: String? = null

        fun consume(choice: JsonObject, onTextDelta: (String) -> Unit) {
            choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.let { finishReason = it.asString }
            val delta = choice.getAsJsonObject("delta") ?: return
            delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let {
                text.append(it)
                onTextDelta(it)
            }
            delta.getAsJsonArray("tool_calls")?.forEach { element -> accumulate(element.asJsonObject) }
        }

        /** Arguments arrive as string fragments keyed by index, invalid JSON until the last. */
        private fun accumulate(call: JsonObject) {
            val partial = calls.getOrPut(call.get("index")?.asInt ?: 0) { PartialCall() }
            call.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { partial.id = it }
            val function = call.getAsJsonObject("function") ?: return
            function.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { partial.name = it }
            function.get("arguments")?.takeIf { !it.isJsonNull }?.asString
                ?.let { partial.arguments.append(it) }
        }

        fun build() = LlmResponse(
            text = text.toString(),
            toolCalls = calls.values.mapNotNull { it.build() },
            // Mapped to the neutral vocabulary so the loop has one set of reasons to read.
            stopReason = when (finishReason) {
                "tool_calls" -> "tool_use"
                "content_filter" -> "refusal"
                else -> finishReason
            },
        )
    }

    private class PartialCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

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
