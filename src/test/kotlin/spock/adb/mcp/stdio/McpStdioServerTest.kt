package spock.adb.mcp.stdio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.FakeToolContext
import spock.adb.mcp.McpProtocol
import spock.adb.mcp.tools.ToolRegistry
import java.io.BufferedReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The stdio transport, exercised over a real pipe against the real [McpProtocol].
 *
 * The point of these tests is that nothing about the protocol is re-implemented for stdio:
 * `initialize`, `tools/list`, `tools/call`, unknown tools and malformed JSON are answered by
 * the same object that answers them over HTTP, and the answers arrive correctly framed.
 */
class McpStdioServerTest {

    private val context = FakeToolContext()
    private val protocol = McpProtocol(contextProvider = { context })

    private val toServer = PipedOutputStream()
    private val fromServer = PipedInputStream(BUFFER)
    private lateinit var responses: BufferedReader
    private lateinit var server: McpStdioServer
    private lateinit var thread: Thread

    private fun start(handle: (String) -> String? = protocol::handle) {
        val input = PipedInputStream(toServer, BUFFER)
        val output = PipedOutputStream(fromServer)
        responses = fromServer.bufferedReader()
        server = McpStdioServer(handle)
        thread = Thread { server.serve(input, output) }.apply { isDaemon = true; start() }
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun send(line: String) {
        toServer.write((line + "\n").toByteArray())
        toServer.flush()
    }

    private fun read(): JsonObject =
        JsonParser.parseString(responses.readLine()).asJsonObject

    @Test
    fun `initialize is answered by the shared protocol implementation`() {
        start()
        send("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")

        val result = read().getAsJsonObject("result")
        assertEquals(McpProtocol.PROTOCOL_VERSION, result.get("protocolVersion").asString)
        assertEquals("spock-adb", result.getAsJsonObject("serverInfo").get("name").asString)
    }

    @Test
    fun `tools list returns the same registry the HTTP transport serves`() {
        start()
        send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        val tools = read().getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(ToolRegistry.all().size, tools.size())
    }

    @Test
    fun `a tool call runs the tool and returns its content`() {
        start()
        send(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call",
               "params":{"name":"android_list_devices","arguments":{}}}""".replace("\n", " "),
        )

        val result = read().getAsJsonObject("result")
        assertFalse(result.get("isError").asBoolean)
        assertTrue(result.getAsJsonArray("content").toString().contains("emulator-5554"))
    }

    @Test
    fun `an invalid request with no method is a JSON-RPC error`() {
        start()
        send("""{"jsonrpc":"2.0","id":4}""")

        assertEquals(McpProtocol.INVALID_REQUEST, read().getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun `malformed JSON is a parse error, produced by the protocol and not by the transport`() {
        start()
        send("""{"jsonrpc": "2.0", "id": 5, "method":""")

        assertEquals(McpProtocol.PARSE_ERROR, read().getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun `an unknown tool is a tool error the agent can read`() {
        start()
        send("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"android_nope"}}""")

        val result = read().getAsJsonObject("result")
        assertTrue(result.get("isError").asBoolean)
        assertTrue(result.getAsJsonArray("content").toString().contains("Unknown tool"))
    }

    @Test
    fun `a failing tool reports the failure as a tool error, not a dropped session`() {
        start()
        send(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call",
               "params":{"name":"android_select_device","arguments":{"deviceSerial":"missing"}}}"""
                .replace("\n", " "),
        )

        assertTrue(read().getAsJsonObject("result").get("isError").asBoolean)

        // The session survives it: the next request is answered normally.
        send("""{"jsonrpc":"2.0","id":8,"method":"ping"}""")
        assertEquals(8, read().get("id").asInt)
    }

    @Test
    fun `a notification is not answered`() {
        start()
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        send("""{"jsonrpc":"2.0","id":9,"method":"ping"}""")

        // The first line on the stream is the ping's response, so nothing was written for the
        // notification.
        assertEquals(9, read().get("id").asInt)
    }

    @Test
    fun `responses are one per line, so a client can frame them`() {
        start()
        send("""{"jsonrpc":"2.0","id":10,"method":"initialize"}""")

        val line = responses.readLine()
        assertFalse(line.contains('\n'))
        // instructions and change notes contain prose; none of it may break the frame.
        JsonParser.parseString(line).asJsonObject
    }

    // ---- cancellation and lifecycle ----
    //
    // These use a controllable handler rather than the real protocol: no registered tool
    // blocks long enough to be cancelled on purpose, and a test that raced a real ADB call
    // would be flaky rather than meaningful. Cancellation is a transport concern in any case —
    // the protocol never sees it.

    private val started = CountDownLatch(1)
    private val interrupted = AtomicBoolean(false)

    private fun slowHandler(raw: String): String? {
        val request = JsonParser.parseString(raw).asJsonObject
        val id = request.get("id")
        if (request.get("method").asString == "slow") {
            started.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30))
            } catch (e: InterruptedException) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
        }
        return """{"jsonrpc":"2.0","id":$id,"result":{}}"""
    }

    @Test
    fun `a cancelled request is interrupted and never answered`() {
        start(::slowHandler)
        send("""{"jsonrpc":"2.0","id":11,"method":"slow"}""")
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the slow call should have started")

        send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":11}}""")
        send("""{"jsonrpc":"2.0","id":12,"method":"ping"}""")

        // Id 12 is the next line on the wire: the cancelled request produced no response,
        // which is what the MCP spec requires — the client has stopped waiting for one.
        assertEquals(12, read().get("id").asInt)
        assertTrue(interrupted.get(), "a cancelled call must actually be interrupted")
    }

    @Test
    fun `reading continues while a slow call is running`() {
        start(::slowHandler)
        send("""{"jsonrpc":"2.0","id":13,"method":"slow"}""")
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        // If requests were served on the reading thread, a cancellation arriving behind a slow
        // call could never be read, and cancellation would not work at all.
        send("""{"jsonrpc":"2.0","id":14,"method":"ping"}""")
        assertEquals(14, read().get("id").asInt)
    }

    @Test
    fun `the session ends when the client closes the stream`() {
        start()
        send("""{"jsonrpc":"2.0","id":15,"method":"ping"}""")
        read()

        toServer.close()

        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        assertFalse(thread.isAlive, "serving should stop at end of stream, not spin")
    }

    @Test
    fun `shutdown interrupts work still in flight`() {
        start(::slowHandler)
        send("""{"jsonrpc":"2.0","id":16,"method":"slow"}""")
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        server.shutdown()

        // A tool call blocked on a device must not keep threads alive after the client has gone.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
        while (!interrupted.get() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue(interrupted.get(), "shutdown must interrupt in-flight work")
    }

    @Test
    fun `an exception escaping the protocol does not end the session`() {
        start { raw -> if (raw.contains("boom")) error("boom") else """{"jsonrpc":"2.0","id":18,"result":{}}""" }
        send("""{"jsonrpc":"2.0","id":17,"method":"boom"}""")
        send("""{"jsonrpc":"2.0","id":18,"method":"ping"}""")

        assertEquals(18, read().get("id").asInt)
    }

    private companion object {
        const val BUFFER = 1 shl 16
        const val TIMEOUT_SECONDS = 10L
        const val POLL_MS = 25L
    }
}
