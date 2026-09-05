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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val selectedProject = AtomicReference<String?>(null)
    private val history = McpRequestHistory()

    /**
     * Read on a server thread for every tool call, written from the settings dialog. Held as
     * an immutable set that is *replaced* rather than mutated, so a reader sees either the
     * old membership or the new one and never a set being rebuilt underneath it.
     */
    private val disabledToolNames = AtomicReference<Set<String>>(emptySet())

    /**
     * Both built on first use: an IDE where the server is never enabled reads no file and
     * starts no thread.
     */
    private val historyStore: McpHistoryStore by lazy {
        McpHistoryStore(
            file = endpointDirectory().resolve(HISTORY_FILE),
            capacity = settings.historySize,
            onError = { message, error -> log.warn("$message; the activity view is unaffected", error) },
        )
    }
    private val historyWriter: McpHistoryWriter by lazy { McpHistoryWriter(historyStore) }
    private val historyLoaded = AtomicBoolean(false)

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
        disabledToolNames.set(state.disabledTools.toSet())
        // Off the calling thread: this runs during IDE startup and the file can hold thousands
        // of records. Nothing waits on it — the activity view shows what has arrived so far,
        // and a call recorded while it is in flight is kept rather than overwritten.
        ApplicationManager.getApplication().executeOnPooledThread(::ensureHistoryLoaded)
    }

    @Synchronized
    fun start(): Result<Int> = runCatching {
        // Already listening. Starting again would replace the HTTP server and strand the
        // previous stdio bridge's threads, so a double click, or a start racing an
        // auto-start, is answered with the port that is already bound. Restarting is
        // Restart's job.
        server?.port?.let { return@runCatching it }

        if (settings.token.isBlank()) settings.token = generateToken()

        val mcpProtocol = McpProtocol(
            contextProvider = { McpToolContext(selectedSerial, selectedProject) },
            auditLog = ::record,
            isToolEnabled = ::isToolEnabled,
        )
        protocol = mcpProtocol
        ensureHistoryLoaded()
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
     * [start], off the calling thread.
     *
     * Both transitions do real blocking work — [start] binds two sockets and writes the
     * stdio endpoint descriptor, and [stop] waits for live stdio sessions to end before
     * releasing their threads — so neither belongs on the EDT, where stopping the server
     * with a client attached froze the tool window until the wait expired.
     *
     * [onResult] runs on the pooled thread, not the EDT. Callers marshal it themselves, as
     * every other background call in the plugin does, so each can also carry its own "is my
     * component still alive" condition.
     */
    fun startAsync(onResult: (Result<Int>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread { onResult(start()) }
    }

    /** [stop], off the calling thread. [onDone] runs on the pooled thread. See [startAsync]. */
    fun stopAsync(onDone: () -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // finally, not a plain sequence: the caller re-enables its controls in [onDone],
            // and a stop that failed part-way must not leave them disabled for good.
            try {
                stop()
            } finally {
                onDone()
            }
        }
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

    /**
     * Whether [toolName] may run at all.
     *
     * Both ways in consult this — the MCP transports through [McpProtocol], the built-in
     * assistant through `RegistryAgentTools` — so the switch means the same thing wherever
     * the call came from.
     */
    fun isToolEnabled(toolName: String): Boolean = ToolGate.isEnabled(toolName, disabledToolNames.get())

    /** The tools switched off, for the settings screen. */
    val disabledTools: Set<String> get() = disabledToolNames.get()

    /**
     * Replaces the set of disabled tools.
     *
     * Takes effect on the next call, on a running server: the predicate is consulted per call
     * rather than baked into the tool list, so turning a tool off does not need a restart and
     * cannot be outrun by a client that cached `tools/list`.
     */
    fun setDisabledTools(names: Set<String>) {
        // Sorted so the settings file does not churn on save, and so a diff of it is readable.
        val stored = names.toSortedSet().toMutableSet()
        settings.disabledTools = stored
        disabledToolNames.set(stored.toSet())
    }

    /**
     * Most recent tool calls, newest first, for the activity view.
     *
     * Reads memory only. The panel calls this on the EDT, and parsing the history file there
     * would be a visible hitch every time the tool window opens.
     */
    fun recentCalls(): List<McpCall> = history.all()

    fun queryHistory(filter: McpHistoryFilter): List<McpCall> = history.query(filter)

    fun knownTools(): List<String> = history.knownTools()

    fun knownClients(): List<String> = history.knownClients()

    fun clearHistory() {
        history.clear()
        historyWriter.clear()
    }

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
            // The in-memory cap is authoritative; the file follows it so a lowered cap
            // actually shrinks what is kept on disk rather than only what is displayed.
            historyStore.capacity = history.capacity
        }

    /**
     * Brings the persisted history into memory, once.
     *
     * Never on the EDT: it opens and parses a file that is capped, but capped at thousands of
     * records. Both callers are already off it — [start] runs on a pooled thread through
     * [startAsync], and [loadState] schedules this on one.
     */
    private fun ensureHistoryLoaded() {
        if (!historyLoaded.compareAndSet(false, true)) return
        history.capacity = settings.historySize
        historyStore.capacity = history.capacity
        history.prepend(historyStore.load())
    }

    private fun record(call: McpCall) {
        history.record(call)
        historyWriter.record(call)

        // Destructive calls reach the IDE log as well as the history file. The history is the
        // developer's view and can be cleared from the panel; the log is the one an incident is
        // reconstructed from, and the two should not be lost by the same action.
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

    override fun dispose() {
        stop()
        historyWriter.shutdown()
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TOKEN_BYTES = 32

        /** Beside the stdio endpoint descriptor, under the IDE config directory. */
        const val HISTORY_FILE = "mcp-history.ndjson"

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
    /**
     * Tools the developer has switched off, by name.
     *
     * Stored as the exception rather than the allow-list so a tool added by a plugin update
     * is available by default: an allow-list would silently withhold every new tool from a
     * developer who had once opened this screen, which reads as the update being broken.
     */
    var disabledTools: MutableSet<String> = mutableSetOf(),
)
