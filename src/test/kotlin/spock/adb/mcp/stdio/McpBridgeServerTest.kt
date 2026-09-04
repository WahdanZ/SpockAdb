package spock.adb.mcp.stdio

import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import spock.adb.mcp.FakeToolContext
import spock.adb.mcp.McpProtocol
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The bridge is what makes stdio reachable from outside the IDE, so its security properties
 * are tested rather than asserted in a comment: the socket and the descriptor holding the
 * token are unreadable by other users, and a connection that cannot present the token is
 * closed before any protocol message is served.
 *
 * The launcher is exercised as a real spawned process, because the things most likely to be
 * wrong about it — that it exits when told to, and that it never writes anything but protocol
 * messages to stdout — are only true of an actual process.
 */
class McpBridgeServerTest {

    private val token = "bridge-test-token"
    private val protocol = McpProtocol(contextProvider = { FakeToolContext() })
    private var bridge: McpBridgeServer? = null

    private fun start(directory: Path): McpBridgeServer.Endpoint {
        val server = McpBridgeServer(protocol::handle, token)
        bridge = server
        return server.start(directory)
    }

    @AfterEach
    fun stop() {
        bridge?.dispose()
    }

    private fun connect(endpoint: McpBridgeServer.Endpoint): SocketChannel {
        assumeTrue(endpoint.transport == McpBridgeServer.Transport.UNIX, "no Unix domain sockets here")
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(UnixDomainSocketAddress.of(endpoint.socketPath!!))
        return channel
    }

    @Test
    fun `binds a socket and describes it in a descriptor file`(@TempDir directory: Path) {
        val endpoint = start(directory)

        assertTrue(Files.exists(endpoint.descriptorFile))
        val properties = Properties().apply {
            Files.newInputStream(endpoint.descriptorFile).use { load(it) }
        }
        assertEquals(token, properties.getProperty("token"))
        assertTrue(properties.getProperty("transport") in setOf("unix", "tcp"))
    }

    @Test
    fun `the token file is readable only by its owner`(@TempDir directory: Path) {
        val endpoint = start(directory)
        val permissions = runCatching { Files.getPosixFilePermissions(endpoint.descriptorFile) }.getOrNull()
        assumeTrue(permissions != null, "not a POSIX filesystem")

        // The whole point of keeping the token out of the client config is that the file it
        // lives in is not readable by anything else on the machine.
        assertEquals("rw-------", PosixFilePermissions.toString(permissions!!))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
    }

    @Test
    fun `serves the shared protocol once the token is presented`(@TempDir directory: Path) {
        val endpoint = start(directory)
        connect(endpoint).use { channel ->
            val out = Channels.newOutputStream(channel)
            val responses = Channels.newInputStream(channel).bufferedReader()

            out.write("$token\n".toByteArray())
            out.write("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""".toByteArray())
            out.write('\n'.code)
            out.flush()

            val result = JsonParser.parseString(responses.readLine()).asJsonObject.getAsJsonObject("result")
            assertEquals(McpProtocol.PROTOCOL_VERSION, result.get("protocolVersion").asString)
        }
    }

    @Test
    fun `closes a connection that presents the wrong token`(@TempDir directory: Path) {
        val endpoint = start(directory)
        connect(endpoint).use { channel ->
            val out = Channels.newOutputStream(channel)
            val responses = Channels.newInputStream(channel).bufferedReader()

            out.write("not-the-token\n".toByteArray())
            out.write("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""".toByteArray())
            out.write('\n'.code)
            out.flush()

            assertNull(responses.readLine(), "an unauthorised connection must be closed, not served")
        }
    }

    @Test
    fun `a connection that never presents a token is closed`(@TempDir directory: Path) {
        // A connection that opens and then says nothing would otherwise hold a thread for as
        // long as it liked, and enough of them would starve real clients of the pool — which
        // any local process can attempt in the TCP fallback.
        val server = McpBridgeServer(protocol::handle, token, handshakeTimeoutSeconds = 1)
        bridge = server
        val endpoint = server.start(directory)

        connect(endpoint).use { channel ->
            val responses = Channels.newInputStream(channel).bufferedReader()
            // Deliberately send nothing at all. readLine returns null when the far end closes,
            // so this blocks until the deadline does its job, or the test times out.
            assertNull(responses.readLine(), "a silent connection should be closed, not held")
        }
    }

    @Test
    fun `stopping removes the socket and the token file`(@TempDir directory: Path) {
        val endpoint = start(directory)
        bridge!!.stop()

        // A stale descriptor would point a client at an endpoint that is no longer listening,
        // and a leftover token file is a credential outliving the thing it authorises.
        assertFalse(Files.exists(endpoint.descriptorFile))
        endpoint.socketPath?.let { assertFalse(Files.exists(it)) }
    }

    // ---- the launcher, as a real process ----

    /**
     * Spawns the launcher from its own code source rather than from `java.class.path`.
     *
     * The launcher has no dependencies — that is the point of it being plain Java — so the jar
     * or class directory holding it is the entire classpath it needs. It is also the same way
     * the generated client configuration resolves it, and it does not care whether the test
     * runner handed us a real classpath or a manifest-only one.
     */
    private fun launch(descriptor: Path?): Process {
        val source = SpockAdbStdioLauncher::class.java.protectionDomain?.codeSource?.location
        assumeTrue(source != null, "cannot locate the launcher to spawn it")
        val command = listOfNotNull(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            Path.of(source!!.toURI()).toString(),
            SpockAdbStdioLauncher::class.java.name,
            descriptor?.toString(),
        )
        return ProcessBuilder(command).start()
    }

    @Test
    fun `a spawned launcher relays a session and exits when its stdin closes`(@TempDir directory: Path) {
        val endpoint = start(directory)
        assumeTrue(endpoint.transport == McpBridgeServer.Transport.UNIX, "no Unix domain sockets here")
        val process = launch(endpoint.descriptorFile)
        try {
            val stdin = process.outputStream
            val stdout = process.inputStream.bufferedReader()

            stdin.write("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""".toByteArray())
            stdin.write('\n'.code)
            stdin.flush()

            val response = JsonParser.parseString(stdout.readLine()).asJsonObject
            assertEquals(1, response.get("id").asInt)

            // Closing stdin is how an MCP client ends a stdio session.
            stdin.close()
            assertTrue(
                process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the launcher must exit when its client goes away, not linger",
            )
            // Everything after the response must have gone to stderr instead.
            assertNull(stdout.readLine(), "stdout carries protocol messages and nothing else")

            // This is the ordinary way a session ends, so it is a success, and silent. Tearing
            // the connection down from the stdin pump instead of half-closing it used to abort
            // the read still in flight and report the clean shutdown as a lost connection.
            assertEquals(0, process.exitValue(), "a client closing stdin is not a failure")
            // Asserting the absence of the failure rather than an empty stderr: a JVM may
            // print banners of its own there (JAVA_TOOL_OPTIONS, CDS warnings), and a test
            // that breaks on those would be testing the environment, not the launcher.
            assertFalse(
                process.errorStream.readBytes().decodeToString().contains("lost the connection"),
                "a clean shutdown must not be reported as a lost connection",
            )
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a launcher whose token is stale fails loudly rather than exiting quietly`(@TempDir directory: Path) {
        val endpoint = start(directory)
        assumeTrue(endpoint.transport == McpBridgeServer.Transport.UNIX, "no Unix domain sockets here")

        // A descriptor pointing at the real endpoint with the wrong token — what a client is
        // left holding after the token is rotated. The bridge closes the connection without a
        // word, so from the launcher this is indistinguishable from a stopped server, and both
        // used to look like a clean, silent, successful exit.
        val stale = directory.resolve("stale.properties")
        Files.newOutputStream(stale).use { out ->
            Properties().apply {
                setProperty("transport", "unix")
                setProperty("socket", endpoint.socketPath!!.toString())
                setProperty("token", "not-the-token")
            }.store(out, null)
        }

        val process = launch(stale)
        try {
            val stdin = process.outputStream
            stdin.write("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""".toByteArray())
            stdin.write('\n'.code)
            runCatching { stdin.flush() }

            assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(process.exitValue() == 0, "a rejected session is a failure, not a quiet success")
            assertTrue(
                process.errorStream.readBytes().decodeToString().contains("closed the connection"),
                "the developer should be told the IDE hung up, not left guessing",
            )
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a launcher given no descriptor explains how to get one`() {
        // The descriptor path is required: the IDE writes it under its own config directory,
        // which differs per IDE, version and platform, so no default could name it correctly.
        val process = launch(descriptor = null)
        try {
            assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(process.exitValue() == 0)
            assertTrue(process.inputStream.readBytes().isEmpty(), "nothing may go to stdout")
            assertTrue(
                process.errorStream.readBytes().decodeToString().contains("Copy MCP Client Configuration"),
                "the message should say where to get a descriptor",
            )
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a launcher with no endpoint fails on stderr and writes nothing to stdout`(@TempDir directory: Path) {
        val process = launch(directory.resolve("absent.properties"))
        try {
            assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(process.exitValue() == 0, "an unreachable IDE is a failure, and must be reported as one")
            assertTrue(process.inputStream.readBytes().isEmpty(), "nothing may be written to the protocol stream")
            assertTrue(
                process.errorStream.readBytes().decodeToString().contains("Start MCP Server"),
                "the developer should be told how to fix it",
            )
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `a launcher exits when the IDE stops the bridge`(@TempDir directory: Path) {
        val endpoint = start(directory)
        assumeTrue(endpoint.transport == McpBridgeServer.Transport.UNIX, "no Unix domain sockets here")
        val process = launch(endpoint.descriptorFile)
        try {
            val stdin = process.outputStream
            stdin.write("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray())
            stdin.write('\n'.code)
            stdin.flush()
            process.inputStream.bufferedReader().readLine()

            bridge!!.stop()

            assertTrue(
                process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "stopping the server must end the client's session, not strand it",
            )
            // The IDE hanging up while the client is still talking is a failure, however
            // quietly it happens. Exiting 0 would tell the client the server started and then
            // chose to do nothing.
            assertFalse(process.exitValue() == 0, "a server that vanished mid-session is not success")
        } finally {
            process.destroyForcibly()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
