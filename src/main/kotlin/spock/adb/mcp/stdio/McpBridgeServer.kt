package spock.adb.mcp.stdio

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The IDE end of the stdio transport.
 *
 * An MCP client speaking stdio *spawns* its server and talks to the child's stdin and stdout.
 * The tools cannot live in that child: they need this IDE's ADB bridge, its project model and
 * its confirmation dialogs. So the child ([SpockAdbStdioLauncher]) is a byte relay with no
 * knowledge of MCP at all, and this class is what it relays to — one local stream endpoint
 * whose sessions are served by [McpStdioServer], which hands every message to the same
 * [spock.adb.mcp.McpProtocol] instance the HTTP transport uses.
 *
 * The wire is identical to real stdio — newline-delimited JSON-RPC — so the relay never has to
 * understand, reframe or rewrite anything passing through it.
 *
 * Security. A Unix domain socket is used where the platform has one: it lives in a directory
 * only the developer can enter, so the filesystem does the authorisation and no credential
 * needs to exist. Where it is unavailable the endpoint falls back to a loopback TCP port,
 * which any local process can reach, so a token is required on both — one line, checked before
 * the session starts. The token lives in a `600` file that the client config points at rather
 * than embeds, so pasting a config into a client, or into a chat, does not paste a credential.
 */
class McpBridgeServer(
    private val handle: (String) -> String?,
    private val token: String,
    private val diagnostics: (String, Throwable?) -> Unit = { _, _ -> },
) {

    private val running = AtomicBoolean(false)
    private var channel: ServerSocketChannel? = null

    /**
     * Live sessions, so stopping the server actually ends them.
     *
     * The connection has to be closed as well as the session shut down: a session sits blocked
     * reading its socket, and closing that socket is the only thing that unblocks it. Without
     * it the relay process on the other end waits for input that will never come, and survives
     * the server it was talking to.
     */
    private val sessions = java.util.Collections.synchronizedList(mutableListOf<Session>())

    private class Session(val channel: SocketChannel, val server: McpStdioServer)

    private val accepts = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpockAdb-MCP-bridge-accept").apply { isDaemon = true }
    }
    private val connections = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "SpockAdb-MCP-bridge").apply { isDaemon = true }
    }

    @Volatile
    var endpoint: Endpoint? = null
        private set

    /**
     * Binds the endpoint and writes [Endpoint.descriptorFile] describing it.
     *
     * @param directory where the socket and descriptor live. Created `700` if absent, so a
     *   Unix domain socket is unreachable by other users.
     */
    @Synchronized
    fun start(directory: Path): Endpoint {
        stop()
        Files.createDirectories(directory)
        restrictToOwner(directory)

        val bound = bindUnixDomain(directory) ?: bindLoopback()
        channel = bound.first
        val descriptor = writeDescriptor(directory, bound.second)
        endpoint = descriptor
        running.set(true)

        accepts.execute { acceptLoop() }
        diagnostics("MCP stdio bridge listening on ${descriptor.describe()}", null)
        return descriptor
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            // Still clean up a half-started bind.
            closeChannel()
            return
        }
        synchronized(sessions) { sessions.toList() }.forEach { session ->
            session.server.shutdown()
            runCatching { session.channel.close() }
        }
        sessions.clear()
        closeChannel()
        endpoint?.let { runCatching { Files.deleteIfExists(it.descriptorFile) } }
        endpoint = null
        diagnostics("MCP stdio bridge stopped", null)
    }

    /** Frees the executors. The server cannot be started again afterwards. */
    fun dispose() {
        stop()
        accepts.shutdownNow()
        connections.shutdownNow()
        // Wait for them to actually go. The accept thread is not unblocked by an interrupt —
        // only by stop() closing the channel — so returning before it has noticed would leave
        // a thread running after the object that owns it is gone.
        runCatching { accepts.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS) }
        runCatching { connections.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS) }
    }

    private fun closeChannel() {
        channel?.let { open ->
            // Read the address before closing: a closed channel throws ClosedChannelException
            // rather than reporting what it used to be bound to.
            val socketFile = runCatching { open.localAddress as? UnixDomainSocketAddress }
                .getOrNull()?.path
            runCatching { open.close() }
            // A Unix domain socket is a file and does not remove itself.
            socketFile?.let { runCatching { Files.deleteIfExists(it) } }
        }
        channel = null
    }

    private fun bindUnixDomain(directory: Path): Pair<ServerSocketChannel, Endpoint>? {
        val socketPath = directory.resolve(SOCKET_NAME)
        // sun_path is a fixed-size field in the kernel — a little over 100 bytes on every
        // platform that has one. An IDE config directory nested deeply enough to overflow it
        // is unusual but real, and binding would then fail for an obscure-looking reason.
        if (socketPath.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_SOCKET_PATH_BYTES) {
            diagnostics("Socket path too long for a Unix domain socket, using loopback TCP", null)
            return null
        }

        return try {
            Files.deleteIfExists(socketPath)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(socketPath))
            server to Endpoint(
                transport = Transport.UNIX,
                socketPath = socketPath,
                port = null,
                token = token,
                descriptorFile = directory.resolve(DESCRIPTOR_NAME),
            )
        } catch (e: UnsupportedOperationException) {
            // Some platforms and some JDK builds have no AF_UNIX. Fall back rather than lose
            // the transport, and say which path was taken so it is not a silent difference.
            diagnostics("Unix domain sockets unavailable, using loopback TCP for the MCP stdio bridge", e)
            null
        } catch (e: IOException) {
            diagnostics("Could not bind the MCP stdio Unix socket, using loopback TCP", e)
            null
        }
    }

    private fun bindLoopback(): Pair<ServerSocketChannel, Endpoint> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val port = (server.localAddress as InetSocketAddress).port
        return server to Endpoint(
            transport = Transport.TCP,
            socketPath = null,
            port = port,
            token = token,
            descriptorFile = Path.of(""),
        )
    }

    /**
     * Waits for the next connection.
     *
     * Null means stop accepting, for either reason: the channel is gone, or accept() threw —
     * which is how [stop] unblocks this loop, since closing the channel is the only thing that
     * does. Both collapse into one value so the loop has a single exit.
     */
    private fun acceptNext(): SocketChannel? = try {
        channel?.accept()
    } catch (e: IOException) {
        if (running.get()) diagnostics("MCP stdio bridge accept failed", e)
        null
    }

    // The accept loop must survive one bad connection: a client that dies mid-handshake is
    // routine and must not take the endpoint down with it.
    @Suppress("TooGenericExceptionCaught")
    private fun acceptLoop() {
        while (running.get()) {
            val connection = acceptNext() ?: break
            connections.execute {
                try {
                    session(connection)
                } catch (e: Exception) {
                    diagnostics("MCP stdio session failed", e)
                } finally {
                    runCatching { connection.close() }
                }
            }
        }
    }

    private fun session(connection: SocketChannel) {
        val reader = BufferedReader(
            InputStreamReader(Channels.newInputStream(connection), StandardCharsets.UTF_8),
        )
        val writer = OutputStreamWriter(
            Channels.newOutputStream(connection),
            StandardCharsets.UTF_8,
        )

        val presented = reader.readLine()
        if (presented == null || !matchesToken(presented.trim())) {
            // Deliberately terse and unlogged in detail: do not tell an unauthorised caller
            // how to authorise.
            diagnostics("Rejected an MCP stdio connection with a bad token", null)
            return
        }

        val session = Session(connection, McpStdioServer(handle, diagnostics))
        sessions += session
        try {
            session.server.serve(reader, writer)
        } finally {
            sessions -= session
            session.server.shutdown()
        }
    }

    private fun matchesToken(presented: String): Boolean = presented.isNotEmpty() &&
        MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            token.toByteArray(StandardCharsets.UTF_8),
        )

    private fun writeDescriptor(directory: Path, endpoint: Endpoint): Endpoint {
        val file = directory.resolve(DESCRIPTOR_NAME)
        val complete = endpoint.copy(descriptorFile = file)

        val properties = Properties().apply {
            setProperty("transport", complete.transport.name.lowercase())
            complete.socketPath?.let { setProperty("socket", it.toString()) }
            complete.port?.let { setProperty("port", it.toString()) }
            setProperty("token", complete.token)
        }
        // Written before the permissions are tightened, so create it empty and restrict it
        // first: a token must never exist, even briefly, as a world-readable file.
        Files.deleteIfExists(file)
        Files.createFile(file)
        restrictToOwner(file)
        Files.newOutputStream(file).use { properties.store(it, "Spock ADB MCP stdio endpoint") }
        return complete
    }

    /**
     * Best effort `700` / `600`.
     *
     * POSIX permissions are the real protection for the Unix socket. On Windows the ACL model
     * is different and the token is what protects the loopback port, so a failure here is
     * reported rather than fatal.
     */
    private fun restrictToOwner(path: Path) {
        try {
            val view = Files.getFileAttributeView(
                path,
                java.nio.file.attribute.PosixFileAttributeView::class.java,
            ) ?: return
            val permissions = if (Files.isDirectory(path)) "rwx------" else "rw-------"
            view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString(permissions))
        } catch (e: IOException) {
            diagnostics("Could not restrict permissions on $path", e)
        }
    }

    enum class Transport { UNIX, TCP }

    /** Where the bridge is listening, and the descriptor file that tells the launcher so. */
    data class Endpoint(
        val transport: Transport,
        val socketPath: Path?,
        val port: Int?,
        val token: String,
        val descriptorFile: Path,
    ) {
        fun describe(): String = when (transport) {
            Transport.UNIX -> "unix:$socketPath"
            Transport.TCP -> "127.0.0.1:$port"
        }
    }

    companion object {
        const val SOCKET_NAME = "mcp-stdio.sock"

        /** Conservative limit for `sun_path`, which is 104 bytes on macOS and 108 on Linux. */
        private const val MAX_SOCKET_PATH_BYTES = 100
        private const val SHUTDOWN_GRACE_SECONDS = 5L
        const val DESCRIPTOR_NAME = "mcp-stdio.properties"
    }
}
