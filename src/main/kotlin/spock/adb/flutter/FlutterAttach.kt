package spock.adb.flutter

/**
 * What [FlutterSessionService.ensureSession] did, in terms the UI and an agent can explain.
 *
 * [NotReady] is the only outcome worth retrying soon: the caller retries with its own backoff
 * — the service never sleeps or loops — for at most [FlutterSessionService.STARTUP_GRACE_MS]
 * after the process started.
 */
sealed interface FlutterAttachOutcome {

    /** A session on the selected app. [reused] when it was already the current one. */
    data class Connected(val session: FlutterSession, val identity: AppIdentity, val reused: Boolean) :
        FlutterAttachOutcome

    /** `pidof <applicationId>` found no process on the device. */
    data class NotRunning(val serial: String, val applicationId: String) : FlutterAttachOutcome

    /** The caller knows the app is a release build: no VM Service exists to connect to. */
    data class ReleaseBuild(val serial: String, val applicationId: String) : FlutterAttachOutcome

    /**
     * The process is up, but its VM or DDS is not reachable yet — no address in logcat, not yet
     * registered with a Dart Tooling Daemon, or a VM without DDS while `flutter run` may still be
     * attaching. Ask again after [retryAfterMs].
     */
    data class NotReady(val reason: String, val retryAfterMs: Long) : FlutterAttachOutcome

    /**
     * The app runs without a debugger session: its VM answered directly, with no DDS, after the
     * startup window. Not kept — a client on the direct VM keeps `flutter attach` from starting
     * DDS (spike S10). Terminal for this process: asking again answers the same until the pid
     * changes.
     */
    data class NoDdsSession(val identity: AppIdentity, val message: String) : FlutterAttachOutcome

    /**
     * Several Dart Tooling Daemon apps pass as the selected app on the selected device — same
     * pid, and starts that cannot be told apart. Spock does not pick by rank: the caller asks.
     */
    data class Ambiguous(val candidates: List<IdentifiedCandidate>, val reason: String) : FlutterAttachOutcome

    /** Nothing names the app's VM Service, past the startup window. [reason] is fit to show. */
    data class NotFound(val reason: String) : FlutterAttachOutcome

    /** adb or the connection failed. [message] is redacted and fit to show. */
    data class Failed(val message: String) : FlutterAttachOutcome
}

/** A change of the project's current Flutter session. */
sealed interface FlutterSessionChange {

    data class Connected(val session: FlutterSession, val identity: AppIdentity?) : FlutterSessionChange

    /** [session] took the place of [previous], which is closed. */
    data class Replaced(
        val previous: FlutterSession,
        val previousIdentity: AppIdentity?,
        val session: FlutterSession,
        val identity: AppIdentity?,
    ) : FlutterSessionChange

    /** Closed by Spock, or lost. [reason] is fit to show. */
    data class Disconnected(val session: FlutterSession, val identity: AppIdentity?, val reason: String) :
        FlutterSessionChange
}

/**
 * Follows [FlutterSessionService]'s current session. Called on the thread that changed it —
 * a pooled thread, or a session thread when a connection is lost; never the EDT and never under
 * the service's lock. Marshal UI work with `invokeLater`. Under concurrent connects, changes may
 * arrive out of order: act on [FlutterSessionService.current].
 */
fun interface FlutterSessionServiceListener {
    fun sessionChanged(change: FlutterSessionChange)

    /**
     * [session] was just created, and is about to connect: a listener added to it now hears the
     * events DDS replays on connect, which are delivered before [sessionChanged] says the session
     * exists. It may still be refused or fail — act on its events once a change makes it current.
     */
    fun sessionCreated(session: FlutterSession) = Unit
}
