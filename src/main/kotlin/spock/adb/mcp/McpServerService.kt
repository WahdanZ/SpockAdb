package spock.adb.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
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
    private val callLog = ArrayDeque<McpCall>()

    private var server: McpHttpServer? = null

    val isRunning: Boolean get() = server?.port != null
    val port: Int? get() = server?.port
    val token: String get() = settings.token

    override fun getState(): McpSettings = settings

    override fun loadState(state: McpSettings) {
        settings = state
        if (settings.token.isBlank()) settings.token = generateToken()
    }

    @Synchronized
    fun start(): Result<Int> = runCatching {
        if (settings.token.isBlank()) settings.token = generateToken()

        val protocol = McpProtocol(
            contextProvider = { McpToolContext(selectedSerial) },
            auditLog = ::record,
        )
        val httpServer = McpHttpServer(protocol, settings.token)
        val boundPort = httpServer.start(settings.port)
        server = httpServer
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
        settings.enabled = false
    }

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
    @Synchronized
    fun recentCalls(): List<McpCall> = callLog.toList().asReversed()

    @Synchronized
    private fun record(call: McpCall) {
        callLog.addLast(call)
        while (callLog.size > MAX_CALL_LOG) callLog.removeFirst()

        // Destructive calls are recorded in the IDE log too: the in-memory list is lost on
        // restart, and "what did the agent do to my device" must survive that.
        if (call.safety == spock.adb.mcp.tools.ToolSafety.DESTRUCTIVE) {
            log.info("MCP destructive call: ${call.toolName} args=${call.arguments} error=${call.isError}")
        }
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

    override fun dispose() = stop()

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private const val MAX_CALL_LOG = 200

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
)
