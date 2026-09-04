package spock.adb.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import spock.adb.mcp.stdio.McpBridgeServer
import spock.adb.mcp.stdio.SpockAdbStdioLauncher
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the MCP server's lifetime, settings and audit trail.
 *
 * Application-level rather than per-project: an MCP client connects to the IDE, not to one
 * project, and running one server per open project would mean several ports and an ambiguous
 * target device.
 *
 * **Disabled by default.** Enabling it lets any local process holding the token drive a
 * connected device, so it is an explicit opt-in rather than something that silently starts
 * listening when the plugin is installed.
 */
@Service(Service.Level.APP)
@State(name = "SpockAdbMcp", storages = [Storage("spock-adb-mcp.xml")])
class McpServerService : PersistentStateComponent<McpSettings>, Disposable {

    private val log = Logger.getInstance(McpServerService::class.java)

    private var settings = McpSettings()
    private val selectedSerial = AtomicReference<String?>(null)
    private val history = McpRequestHistory()

    private var server: McpHttpServer? = null
    private var bridge: McpBridgeServer? = null
    private var protocol: McpProtocol? = null

    /** Notified after each recorded call so the activity panel can update live. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(McpCall) -> Unit>()

    val isRunning: Boolean get() = server?.port != null
    val port: Int? get() = server?.port
    val token: String get() = settings.token

    /** Where the stdio bridge is listening, or null when it could not be started. */
    val stdioEndpoint: McpBridgeServer.Endpoint? get() = bridge?.endpoint

    override fun getState(): McpSettings = settings

    override fun loadState(state: McpSettings) {
        settings = state
        if (settings.token.isBlank()) settings.token = generateToken()
    }

    @Synchronized
    fun start(): Result<Int> = runCatching {
        if (settings.token.isBlank()) settings.token = generateToken()

        val mcpProtocol = McpProtocol(
            contextProvider = { McpToolContext(selectedSerial) },
            auditLog = ::record,
        )
        protocol = mcpProtocol
        history.capacity = settings.historySize
        val httpServer = McpHttpServer(mcpProtocol, settings.token)
        val boundPort = httpServer.start(settings.port)
        server = httpServer
        startStdioBridge(mcpProtocol)
        settings.enabled = true
        // Remember the port the OS handed out so the generated client config keeps working
        // across restarts.
        settings.port = boundPort
        boundPort
    }.onFailure { log.warn("Could not start the MCP server", it) }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        // dispose(), not stop(): stop() releases the socket but keeps the executors, and
        // start() always builds a fresh McpBridgeServer, so a stop/start cycle — which is
        // exactly what Restart MCP Server does — would strand the previous one's threads.
        bridge?.dispose()
        bridge = null
        protocol = null
        settings.enabled = false
    }

    /**
     * Starts the stdio bridge beside the HTTP transport, on the same [McpProtocol] instance.
     *
     * Both transports therefore share one protocol implementation, one ToolRegistry, one
     * safety model and one audit trail — a call arriving over stdio is recorded, confirmed
     * and logged exactly as the same call over HTTP.
     *
     * A failure here does not fail [start]: stdio is one of two ways in, and losing it should
     * not take the working one down with it.
     */
    private fun startStdioBridge(mcpProtocol: McpProtocol) {
        val stdioBridge = McpBridgeServer(
            handle = mcpProtocol::handle,
            token = settings.token,
            diagnostics = { message, error -> if (error == null) log.info(message) else log.warn(message, error) },
        )
        runCatching { stdioBridge.start(endpointDirectory()) }
            .onSuccess { bridge = stdioBridge }
            .onFailure {
                log.warn("MCP stdio bridge unavailable; the HTTP transport is unaffected", it)
                stdioBridge.dispose()
            }
    }

    /** Per-IDE, so two IDEs running the server at once do not fight over one socket. */
    private fun endpointDirectory(): Path = Path.of(PathManager.getConfigPath(), "spock-adb")

    /** Invalidates the current token, which disconnects every client using it. */
    @Synchronized
    fun regenerateToken(): String {
        settings.token = generateToken()
        if (isRunning) {
            stop()
            start()
        }
        return settings.token
    }

    /** Most recent tool calls, newest first, for the activity view. */
    fun recentCalls(): List<McpCall> = history.all()

    fun queryHistory(filter: McpHistoryFilter): List<McpCall> = history.query(filter)

    fun knownTools(): List<String> = history.knownTools()

    fun knownClients(): List<String> = history.knownClients()

    fun clearHistory() = history.clear()

    /**
     * What the connected client reported at `initialize`.
     *
     * Null when nothing is known. The HTTP transport is stateless, so a client that has not
     * called `initialize` cannot be identified — reported honestly rather than guessed.
     */
    fun connectedClient(): McpClientInfo? = protocol?.connectedClient

    fun addCallListener(listener: (McpCall) -> Unit) {
        listeners += listener
    }

    fun removeCallListener(listener: (McpCall) -> Unit) {
        listeners -= listener
    }

    var historySize: Int
        get() = settings.historySize
        set(value) {
            settings.historySize = value
            history.capacity = value
        }

    private fun record(call: McpCall) {
        history.record(call)

        // Destructive calls are recorded in the IDE log too: the in-memory history is lost
        // on restart, and "what did the agent do to my device" must survive that.
        if (call.safety == spock.adb.mcp.tools.ToolSafety.DESTRUCTIVE) {
            log.info("MCP destructive call: ${call.toolName} args=${call.arguments} error=${call.isError}")
        }
        listeners.forEach { runCatching { it(call) } }
    }

    /**
     * Configuration snippet for an MCP client.
     *
     * Contains the session token, which is a credential: it is generated locally, never
     * leaves the machine unless the developer copies it, and can be rotated.
     */
    fun clientConfiguration(): String {
        val activePort = port ?: settings.port
        return """
            {
              "mcpServers": {
                "spock-adb": {
                  "type": "http",
                  "url": "http://127.0.0.1:$activePort${McpHttpServer.ENDPOINT}",
                  "headers": {
                    "Authorization": "Bearer ${settings.token}"
                  }
                }
              }
            }
        """.trimIndent()
    }

    /**
     * Configuration snippet for a client that speaks stdio.
     *
     * Unlike the HTTP form this carries **no credential**: it points the client at the
     * launcher and at the endpoint descriptor, and the token stays in that `600` file. A
     * config pasted into a chat, a bug report or a shared dotfile therefore gives away
     * nothing — the file it names is readable only by the developer.
     *
     * Built with Gson rather than string interpolation because these are filesystem paths:
     * a Windows path in a hand-rolled JSON string would produce `C:\Users`, which is invalid
     * JSON and would be silently mangled where it is not.
     */
    fun stdioClientConfiguration(): String {
        val descriptor = stdioEndpoint?.descriptorFile?.toString()
            ?: endpointDirectory().resolve(McpBridgeServer.DESCRIPTOR_NAME).toString()

        val server = JsonObject().apply {
            addProperty("command", javaExecutable())
            add(
                "args",
                JsonArray().apply {
                    add("-cp")
                    add(launcherClasspath())
                    add(SpockAdbStdioLauncher::class.java.name)
                    add(descriptor)
                },
            )
        }
        val config = JsonObject().apply {
            add("mcpServers", JsonObject().apply { add("spock-adb", server) })
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(config)
    }

    /**
     * The IDE's own JVM.
     *
     * Using it means the launcher runs on a JDK that is definitely present and definitely new
     * enough, rather than whatever `java` happens to be on the developer's PATH — which on many
     * machines is nothing at all.
     */
    private fun javaExecutable(): String {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", name).toString()
    }

    /** The plugin jar holding the launcher, resolved at runtime rather than guessed. */
    private fun launcherClasspath(): String =
        PathManager.getJarPathForClass(SpockAdbStdioLauncher::class.java)
            ?: PathManager.getPluginsPath()

    override fun dispose() = stop()

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TOKEN_BYTES = 32

        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }
}

/** Persisted MCP settings. */
data class McpSettings(
    /** Off unless the developer turns it on. */
    var enabled: Boolean = false,
    /** 0 asks the OS for a free port; the chosen one is stored back. */
    var port: Int = 0,
    /** Bearer token clients must present. Generated on first use. */
    var token: String = "",
    /** How many tool calls to keep. Bounded so the log cannot grow without limit. */
    var historySize: Int = McpRequestHistory.DEFAULT_CAPACITY,
)
