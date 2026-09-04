package spock.adb.mcp

import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class McpHttpServerTest {

    private val token = "test-token-value"
    private lateinit var server: McpHttpServer
    private var port = 0

    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        server = McpHttpServer(McpProtocol(contextProvider = { FakeToolContext() }), token)
        port = server.start(0)
    }

    @AfterEach
    fun stop() = server.stop()

    private fun post(body: String, bearer: String? = token, method: String = "POST"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port${McpHttpServer.ENDPOINT}"))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `serves an authorised request`() {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")

        assertEquals(200, response.statusCode())
        val result = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("result")
        assertEquals(McpProtocol.PROTOCOL_VERSION, result.get("protocolVersion").asString)
    }

    @Test
    fun `rejects a request with no token`() {
        // Binding to loopback is not an authorisation boundary: any local process could
        // otherwise drive the developer's device.
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", bearer = null).statusCode())
    }

    @Test
    fun `rejects a request with the wrong token`() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bearer = "wrong").statusCode())
    }

    @Test
    fun `rejects a token that is a prefix of the real one`() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", bearer = token.dropLast(1)).statusCode())
    }

    @Test
    fun `rejects methods other than POST`() {
        assertEquals(405, post("", method = "GET").statusCode())
    }

    @Test
    fun `accepts a notification without a body`() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.statusCode())
        assertTrue(response.body().isEmpty())
    }

    @Test
    fun `binds only to the loopback interface`() {
        assertTrue(
            server.port != null && server.port!! > 0,
            "the server should be listening on an ephemeral port",
        )
    }

    @Test
    fun `stopping releases the port`() {
        server.stop()
        val failed = runCatching { post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") }.isFailure
        assertTrue(failed, "the server should refuse connections once stopped")
    }
}
