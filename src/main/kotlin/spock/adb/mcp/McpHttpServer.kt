package spock.adb.mcp

import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * HTTP transport for the MCP server.
 *
 * Built on the JDK's own `com.sun.net.httpserver`, deliberately: Android Studio does not
 * ship IntelliJ's built-in web server (`org.jetbrains.ide.HttpRequestHandler` is absent from
 * the distribution), and adding Netty or Ktor to a plugin that otherwise has one dependency
 * is a large amount of new surface for a localhost JSON endpoint.
 *
 * Security, given this exposes device control over a socket:
 *
 *  - Bound to the loopback interface only, so it is not reachable from the network.
 *  - Every request must carry the session token. Loopback alone is not an authorisation
 *    boundary — any local process, including a web page's local requests, could otherwise
 *    drive the developer's device.
 *  - Destructive tools still require a human to approve each call, independent of the token.
 */
class McpHttpServer(
    private val protocol: McpProtocol,
    private val token: String,
) {

    private val log = Logger.getInstance(McpHttpServer::class.java)
    private var server: HttpServer? = null

    val port: Int? get() = server?.address?.port

    /**
     * @param requestedPort 0 asks the OS for a free port.
     * @return the bound port.
     */
    @Synchronized
    fun start(requestedPort: Int): Int {
        stop()

        val httpServer = HttpServer.create(
            InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort),
            BACKLOG,
        )
        httpServer.createContext(ENDPOINT) { exchange -> handle(exchange) }
        httpServer.executor = Executors.newFixedThreadPool(THREADS) { runnable ->
            Thread(runnable, "SpockAdb-MCP").apply { isDaemon = true }
        }
        httpServer.start()
        server = httpServer

        log.info("Spock ADB MCP server listening on http://127.0.0.1:${httpServer.address.port}$ENDPOINT")
        return httpServer.address.port
    }

    @Synchronized
    fun stop() {
        server?.let {
            it.stop(0)
            log.info("Spock ADB MCP server stopped")
        }
        server = null
    }

    // A request handler must never let an exception escape: the server would keep the
    // socket open and the client would hang rather than see an error.
    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, HTTP_METHOD_NOT_ALLOWED, """{"error":"Use POST"}""")
                return
            }
            if (!isAuthorised(exchange)) {
                // Deliberately terse: do not tell an unauthorised caller how to authorise.
                respond(exchange, HTTP_UNAUTHORIZED, """{"error":"Unauthorized"}""")
                return
            }

            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val response = protocol.handle(body)

            if (response == null) {
                // JSON-RPC notification: accepted, no body.
                exchange.sendResponseHeaders(HTTP_ACCEPTED, -1)
                exchange.close()
            } else {
                respond(exchange, HTTP_OK, response)
            }
        } catch (e: IOException) {
            log.warn("MCP request failed", e)
            runCatching { exchange.close() }
        } catch (e: Exception) {
            log.warn("MCP request failed", e)
            runCatching { respond(exchange, HTTP_SERVER_ERROR, """{"error":"Internal error"}""") }
        }
    }

    private fun isAuthorised(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val presented = header.removePrefix("Bearer ").trim()
        // Constant-time comparison: a naive equals leaks the token a character at a time.
        return presented.isNotEmpty() &&
            java.security.MessageDigest.isEqual(
                presented.toByteArray(StandardCharsets.UTF_8),
                token.toByteArray(StandardCharsets.UTF_8),
            )
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val ENDPOINT = "/mcp"

        private const val BACKLOG = 0
        private const val THREADS = 4
        private const val HTTP_OK = 200
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_SERVER_ERROR = 500
    }
}
