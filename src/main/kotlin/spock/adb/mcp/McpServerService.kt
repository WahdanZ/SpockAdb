package spock.adb.mcp

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.annotations.OptionTag
import spock.adb.mcp.stdio.McpBridgeServer
import spock.adb.mcp.stdio.SpockAdbStdioLauncher
import java.nio.file.Path
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

    /**
     * The session token, generated on first use.
     *
     * Blocking on the first call of an IDE session, which is a keychain read. Every caller
     * either runs on a pooled thread already or asks only while the server is running, by which
     * point [start] has warmed [McpTokenStore]'s cache.
     */
    val token: String get() = sessionToken()

    /**
     * Resolves the session token, migrating a legacy one out of the settings file if that is
     * what is there.
     *
     * Every path to the token goes through here, so the migration cannot race a server start:
     * whichever runs first resolves it, and [McpTokenStore.current] holds the lock for both.
     */
    private fun sessionToken(): String = McpTokenStore.current(
        legacy = { settings.legacyToken },
        onAdopted = { settings.legacyToken = "" },
    )

    /** Where the stdio bridge is listening, or null when it could not be started. */
    val stdioEndpoint: McpBridgeServer.Endpoint? get() = bridge?.endpoint

    /** Live stdio connections. Zero when the bridge is down as well as when nothing is attached. */
    val stdioSessionCount: Int get() = bridge?.sessionCount ?: 0

    override fun getState(): McpSettings = settings

    override fun loadState(state: McpSettings) {
        settings = state
        disabledToolNames.set(state.disabledTools.toSet())
        if (state.legacyToken.isNotBlank()) {
            // A migrated token must clear the plain-text copy before loadState returns, or a
            // settings save that races this startup work can write it straight back to disk.
            sessionToken()
        }
        // Off the calling thread: this runs during IDE startup, the history file can hold
        // thousands of records, and the token warm-up still reads the OS keychain when there is
        // no legacy plaintext token to migrate synchronously. Nothing waits on either — the
        // activity view shows what has arrived so far, and a call recorded while it is in flight
        // is kept rather than overwritten.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (state.legacyToken.isBlank()) {
                sessionToken()
            }
            ensureHistoryLoaded()
        }
    }

    @Synchronized
    fun start(): Result<Int> = runCatching {
        // Already listening. Starting again would replace the HTTP server and strand the
        // previous stdio bridge's threads, so a double click, or a start racing an
        // auto-start, is answered with the port that is already bound. Restarting is
        // Restart's job.
        server?.port?.let { return@runCatching it }

        // Minted here rather than lazily on the first request: this runs on a pooled thread,
        // and the keychain read belongs off the path a client is waiting on.
        val sessionToken = sessionToken()

        val mcpProtocol = McpProtocol(
            contextProvider = { toolContext },
            auditLog = ::record,
            isToolEnabled = ::isToolEnabled,
        )
        protocol = mcpProtocol
        ensureHistoryLoaded()
        val httpServer = McpHttpServer(mcpProtocol, sessionToken)
        val boundPort = httpServer.start(settings.port)
        server = httpServer
        startStdioBridge(mcpProtocol, sessionToken)
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
    private fun startStdioBridge(mcpProtocol: McpProtocol, sessionToken: String) {
        val stdioBridge = McpBridgeServer(
            handle = mcpProtocol::handle,
            token = sessionToken,
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

    /**
     * Invalidates the current token, which disconnects every client using it.
     *
     * Blocking: it writes to the keychain and, when the server is up, restarts it. Throws if
     * the keychain refuses, having changed nothing — the old token keeps working, which is a
     * better failure than a rotation that half happened.
     */
    @Synchronized
    fun regenerateToken(): String {
        val fresh = McpTokenStore.rotate()
        // A legacy attribute that migration could not clear must not outlive the token it holds.
        settings.legacyToken = ""

        if (isRunning) {
            stop()
            // The token is already replaced and every client holding the old one already
            // rejected, so a restart that failed must not be reported as a rotation that went
            // fine: the developer needs to know the server is down, not just that the token
            // changed.
            start().onFailure { throw RestartFailed(it) }
        }
        return fresh
    }

    /** The token was rotated; bringing the server back up afterwards was not possible. */
    class RestartFailed(cause: Throwable) :
        IllegalStateException("the token was rotated, but the server did not restart", cause)

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
     * The context every tool call runs against, whichever way it arrived.
     *
     * Shared by the MCP transports and the in-IDE assistant on purpose: one device selection,
     * one project resolution and one confirmation dialog, so "which phone is this acting on"
     * has a single answer.
     */
    val toolContext: spock.adb.mcp.tools.ToolContext
        get() = McpToolContext(selectedSerial, selectedProject)

    /**
     * The device MCP clients and the assistant are targeting, or null when none has been
     * chosen and calls fall through to whichever device is attached.
     *
     * Exposed so the Devices tab can show it: this selection and the tool window's own are
     * independent, and a developer watching one phone while an agent drives another is a trap
     * worth surfacing rather than documenting.
     */
    val targetedSerial: String? get() = selectedSerial.get()

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

    /**
     * Records one tool call, whichever way it arrived.
     *
     * Public because the in-IDE assistant records through it too: "what touched my device"
     * should have one answer, and a call the assistant made is as much a thing that touched it
     * as an external agent's.
     */
    fun record(call: McpCall) {
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
     * The HTTP server entry for a client config.
     *
     * [includeToken] defaults to false, and that default is the point: the generated text then
     * references `${McpClientConfig.TOKEN_ENV_VAR}` and holds no credential, so pasting it
     * somewhere it should not have gone costs nothing. The literal form still exists for
     * clients that cannot expand environment variables, but a caller has to ask for it — and
     * every caller that does asks the developer first.
     */
    fun httpServerEntry(includeToken: Boolean = false): JsonObject =
        McpClientConfig.httpServer(
            port = port ?: settings.port,
            token = if (includeToken) sessionToken() else null,
        )

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
    fun stdioServerEntry(): JsonObject {
        val descriptor = stdioEndpoint?.descriptorFile?.toString()
            ?: endpointDirectory().resolve(McpBridgeServer.DESCRIPTOR_NAME).toString()

        return McpClientConfig.stdioServer(
            javaExecutable = javaExecutable(),
            classpath = launcherClasspath(),
            launcherClass = SpockAdbStdioLauncher::class.java.name,
            descriptor = descriptor,
        )
    }

    /**
     * Whether the tokenless transport is available, which decides what gets installed and what
     * the panel offers first.
     */
    val prefersStdio: Boolean get() = stdioEndpoint != null

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

    companion object {
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
    /**
     * Where the token used to live, kept only so it can be taken out of the file.
     *
     * The token is a credential for the developer's device and filesystem, and this file syncs
     * with IDE settings and is readable by anything running as the developer. It now lives in
     * [McpTokenStore]; a value still here is one written by an earlier version, and
     * [McpServerService.loadState] moves it and blanks this on the next save. `@OptionTag` keeps
     * the serialised name the old files use, so renaming the property did not orphan them.
     */
    @OptionTag("token")
    var legacyToken: String = "",
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
