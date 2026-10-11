package spock.adb.home

import spock.adb.flutter.navigation.FlutterNavigationState

/**
 * Which Flutter route reads may still land, and when to read again, apart from Swing so it can be
 * tested. Started and answered on the EDT, like [spock.adb.LatestRequest].
 *
 * Home's keyed read and the back stack check are separate requests. A refresh — the settle timer,
 * a `Flutter.Navigation`, an action's re-read, the app or device changing — retires an earlier
 * keyed read, never a check the user is waiting on: a check is retired only by a newer check, or
 * by the session or isolate it read changing, which [current] compares.
 *
 * Home shows the newest read to land, by when it started: a keyed read started before a check
 * that already landed has older news than the check, and does not overwrite it.
 */
internal class FlutterReadRequests {

    /** One read, of [session]'s [isolate]. */
    class Request internal constructor(
        internal val token: Long,
        val check: Boolean,
        val session: Any,
        val isolate: String?,
    )

    private var issued = 0L
    private var latestKeyed = 0L
    private var latestCheck = 0L
    private var shown = 0L
    private var retries = 0

    fun keyed(session: Any, isolate: String?): Request =
        Request(++issued, check = false, session, isolate).also { latestKeyed = it.token }

    fun check(session: Any, isolate: String?): Request =
        Request(++issued, check = true, session, isolate).also { latestCheck = it.token }

    /** The keyed read in flight, if any, is no longer worth showing: the app navigated, or Home has nothing to read. */
    fun retireKeyed() {
        latestKeyed = ++issued
    }

    /** Whether [request] is the latest of its kind: a check's link waits on that one only. */
    fun isLatest(request: Request): Boolean = request.token == if (request.check) latestCheck else latestKeyed

    /**
     * Whether [request]'s answer still describes what Home is about: the latest of its kind, read
     * from the [session] and [isolate] that are current now.
     */
    fun current(request: Request, session: Any?, isolate: String?): Boolean =
        isLatest(request) && session === request.session && isolate == request.isolate

    /** Whether [request]'s answer, [current], may go on Home's row; if so it is the one Home shows now. */
    fun showOnHome(request: Request, session: Any?, isolate: String?): Boolean {
        if (!current(request, session, isolate) || request.token < shown) return false
        shown = request.token
        return true
    }

    /** A new burst of navigation, or a refresh: reads that met a transition may try again. */
    fun newBurst() {
        retries = 0
    }

    /** Whether to read again after [state]: a navigation was under way, and this burst has tries left. */
    fun retryAfter(state: FlutterNavigationState): Boolean {
        if (!state.transient || retries >= MAX_RETRIES) return false
        retries++
        return true
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
