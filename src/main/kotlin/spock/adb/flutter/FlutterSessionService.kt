package spock.adb.flutter

import com.android.ddmlib.IDevice
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import spock.adb.device.ConnectedDevice
import spock.adb.flutter.dtd.DtdDiscovery
import spock.adb.flutter.vmservice.LogcatDiscovery
import spock.adb.flutter.vmservice.PastedUriDiscovery
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceCandidate
import spock.adb.flutter.vmservice.VmServiceDiscovery
import spock.adb.flutter.vmservice.VmServiceException

/**
 * The project's connection to a running Flutter app: at most one [FlutterSession], found
 * through the sources that know the app's address, and closed with the project.
 *
 * Callers — Diagnose and the MCP tools (P5) — call [discover], [connect] and [disconnect] from a
 * pooled thread: each one blocks on files, `adb` or the network. Nothing here touches Swing, and
 * nothing here runs on the EDT except [dispose], which hands the close to a pooled thread.
 */
@Service(Service.Level.PROJECT)
class FlutterSessionService(private val project: Project) : Disposable {

    /** Seams for tests; production uses the real sources. */
    internal var projectPath: () -> String? = { project.basePath }
    internal var dtdDiscovery: (projectPath: String, deviceModel: String?) -> VmServiceDiscovery =
        { path, model -> DtdDiscovery(path, model) }
    internal var logcatDiscovery: (IDevice, String) -> VmServiceDiscovery = ::LogcatDiscovery
    internal var newSession: () -> FlutterSession = { FlutterSession() }
    internal var background: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) }

    @Volatile
    private var disposed = false

    /** The live or last session; null before the first [connect] and after [disconnect]. */
    @Volatile
    var current: FlutterSession? = null
        private set

    /**
     * Every address Spock could connect to for the app on [device], best first:
     * 1. the Dart Tooling Daemons running for this project — DDS's address (needs an SDK whose
     *    DTD has `ConnectedApp`; 3.47.5 confirmed);
     * 2. [pasted], when the developer gave one;
     * 3. the engine's announcement in logcat for [applicationId] — the VM's direct address, a
     *    read-only last resort, so asked for only when nothing above found the app.
     *
     * The Flutter IDE plugin's running-app state belongs between 1 and 2 and is not read yet.
     * A source that fails is logged and skipped. Blocking.
     *
     * @throws IllegalArgumentException when [pasted] holds no usable address, before any other
     * source is asked — the message is fit to show.
     */
    fun discover(device: ConnectedDevice, applicationId: String?, pasted: String? = null): List<VmServiceCandidate> {
        val pastedFound = pasted?.takeIf { it.isNotBlank() }?.let { PastedUriDiscovery(it).discover() }.orEmpty()
        val dtd = projectPath()?.let { path ->
            run("the Dart Tooling Daemon", dtdDiscovery(path, device.info.model.takeIf { it.isNotBlank() }))
        }.orEmpty()
        val found = dtd + pastedFound
        if (found.isNotEmpty() || applicationId.isNullOrBlank()) return found
        return run("logcat", logcatDiscovery(device.device, applicationId))
    }

    /**
     * Connects to [candidate], closing the session before it first. Blocking.
     *
     * @throws VmServiceException when the connection fails; [current] is then null.
     * @throws IllegalStateException once the project is closing.
     */
    @Synchronized
    fun connect(candidate: VmServiceCandidate): FlutterSession {
        check(!disposed) { "The project is closed." }
        disconnect()
        val session = newSession()
        session.connect(candidate)
        current = session
        return session
    }

    /** Closes the session, if any: undoes what it changed in the app and drops the connection. Blocking. */
    @Synchronized
    fun disconnect() {
        val session = current ?: return
        current = null
        session.close()
    }

    /**
     * On project close, often on the EDT: the close talks to the app, and a [connect] may hold
     * the lock for seconds, so both wait on a pooled thread. A connect still running finishes
     * first and is closed right after.
     */
    override fun dispose() {
        disposed = true
        background(Runnable { disconnect() })
    }

    // Exception, not VmServiceException alone: logcat discovery runs adb, whose failures are
    // ddmlib's own checked exceptions, and one failing source must not cost the others.
    @Suppress("TooGenericExceptionCaught")
    private fun run(source: String, discovery: VmServiceDiscovery): List<VmServiceCandidate> = try {
        discovery.discover()
    } catch (e: Exception) {
        log.info("Flutter discovery through $source failed: ${Redaction.scrub(e.message.orEmpty())}")
        emptyList()
    }

    companion object {
        private val log = Logger.getInstance(FlutterSessionService::class.java)

        /**
         * Not the inline `project.service<T>()`, which inlines a call missing before 2023.3
         * (docs/COMPATIBILITY.md).
         */
        fun getInstance(project: Project): FlutterSessionService =
            project.getService(FlutterSessionService::class.java)
    }
}
