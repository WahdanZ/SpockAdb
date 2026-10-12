package spock.adb.mcp.tools

import com.google.gson.JsonObject
import spock.adb.CancellationSignal
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionService
import spock.adb.flutter.SessionState
import spock.adb.flutter.navigation.Confidence
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.flutter.navigation.NavigatorReader
import spock.adb.flutter.navigation.NonNavigatorElements
import spock.adb.flutter.navigation.SessionObjects
import spock.adb.home.FlutterRouteText

/**
 * Reads a Flutter app's current route, for `expectRoute`: through the session Spock already has
 * with the app, never attaching one.
 */
internal fun interface RouteReader {
    /**
     * The navigation state of [packageName] — any app, when null — on [serial]. With [check], also
     * proves no navigator without a key hides the route: a heap walk that pauses the app briefly.
     * Unavailable, with the reason in words, when there is no session to read or it cannot be read.
     * Blocking.
     */
    fun read(context: ToolContext, serial: String, packageName: String?, check: Boolean): FlutterNavigationState
}

/** [RouteReader] on the project's [FlutterSessionService] session, read as Home and App back stack read it. */
internal object SessionRouteReader : RouteReader {

    private val notNavigators = NonNavigatorElements()

    override fun read(
        context: ToolContext,
        serial: String,
        packageName: String?,
        check: Boolean,
    ): FlutterNavigationState {
        val project = context.project ?: return FlutterNavigationState.unavailable(NO_PROJECT)
        val sessions = FlutterSessionService.getInstance(project)
        val session = sessions.current
        val identity = sessions.identity
        val app = packageName ?: "the app"
        if (session == null || identity == null || session.state is SessionState.Disconnected) {
            return FlutterNavigationState.unavailable(noSession(app, serial))
        }
        val same = identity.verified && identity.serial == serial &&
            (packageName == null || identity.applicationId == packageName)
        if (!same) {
            return FlutterNavigationState.unavailable(
                "Spock's Flutter session is with $identity, not $app on $serial. " + SELECT_HINT,
            )
        }
        return read(session, check)
    }

    /** [session]'s route, once it is known to be the app's. */
    // Whatever the VM reads throw, the agent gets a reason rather than a stack trace: Home does
    // the same with its READ_FAILED.
    @Suppress("TooGenericExceptionCaught")
    fun read(session: FlutterSession, check: Boolean): FlutterNavigationState {
        val snapshot = session.snapshot
        (snapshot.state as? SessionState.Paused)?.let { return FlutterNavigationState.unavailable(it.message) }
        val isolate = snapshot.uiIsolateId
            ?: return FlutterNavigationState.unavailable("The session has no Flutter UI isolate selected yet.")
        val objects = SessionObjects(session, isolate, if (check) CHECK_BUDGET_MS else READ_BUDGET_MS)
        val reader = NavigatorReader(objects, notNavigators = notNavigators.scopeTo(session, isolate))
        return try {
            if (check) reader.readChecked() else reader.readKeyed()
        } catch (e: RuntimeException) {
            FlutterNavigationState.unavailable("Spock failed while reading the navigator: ${e.javaClass.simpleName}.")
        }
    }

    private fun noSession(app: String, serial: String) =
        "Spock has no Flutter session with $app on $serial: a debug or profile build run with flutter run " +
            "has one, a release build none. $SELECT_HINT"

    private const val SELECT_HINT = "Select the app in Spock, or call flutter_app_status, which attaches."
    private const val NO_PROJECT = "No project is open: Spock's Flutter session belongs to the project that runs " +
        "the app."

    /** Home's budget for one read: plain object reads, a few per route. */
    private const val READ_BUDGET_MS = 3_000L

    /** App back stack's: a read and a heap walk. */
    private const val CHECK_BUDGET_MS = 5_000L
}

/**
 * `expectRoute`: the Flutter route that should be current after an action — a Flutter app's
 * activity is always `MainActivity`, so `expectActivity` cannot tell its screens apart.
 *
 * Matched on the route's name, exactly and case-sensitively, with one allowance: a name given
 * without a query or fragment also matches the same path with one, so `/item/42` matches the
 * `/item/42?ref=spock` a deep link pushes. An unnamed route — a dialog — matches nothing.
 */
internal class RouteExpectation(val route: String, val timeoutMs: Int, val requestedMs: Int) {

    val capped: Boolean get() = timeoutMs < requestedMs

    fun matches(name: String?): Boolean = when {
        name == null -> false
        name == route -> true
        '?' in route || '#' in route -> false
        else -> name.substringBefore('#').substringBefore('?') == route
    }

    companion object {
        const val ROUTE = "expectRoute"
        const val TIMEOUT = "expectTimeoutMs"
        private const val DEFAULT_TIMEOUT_MS = 5_000
        const val MAX_TIMEOUT_MS = 60_000

        /** Null when no route was asked for. */
        fun from(arguments: JsonObject, canCancel: Boolean): RouteExpectation? {
            val route = arguments.optionalString(ROUTE) ?: return null
            val requested = arguments.optionalInt(TIMEOUT, DEFAULT_TIMEOUT_MS, 0..MAX_TIMEOUT_MS)
            return RouteExpectation(route, WaitForElementTool.timeoutFor(requested, canCancel), requested)
        }

        fun Schema.ObjectBuilder.routeOption() {
            string(
                ROUTE,
                "Flutter: the route name expected after, exact (?query ignored). INCONCLUSIVE without a session.",
            )
        }
    }
}

/**
 * Checks a [RouteExpectation] around one action: the route [before] it, then reads after it until
 * it matches or the time is up. VERIFIED only when it matched after and was read, and did not
 * match, before; INCONCLUSIVE — never NOT OBSERVED — whenever the route could not be read, so a
 * missing session is not mistaken for the wrong screen.
 *
 * Reads are the keyed read Home makes. One that has not matched when the time is up is checked
 * once with the heap walk, which says whether a navigator without a key could be hiding the route;
 * only an exact answer is NOT OBSERVED.
 */
internal class RouteCheck(
    private val expectation: RouteExpectation,
    private val read: (check: Boolean) -> FlutterNavigationState,
    private val signal: CancellationSignal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    class Verdict(val outcome: ActionOutcome, val text: String)

    private val expected = "the Flutter route '${expectation.route}'"

    /** The route before the action. A read that met a transition is tried again, a few times. */
    fun before(): FlutterNavigationState {
        var state = read(false)
        repeat(BEFORE_RETRIES) {
            if (!state.transient || !pause(RETRY_MS)) return state
            state = read(false)
        }
        return state
    }

    /** After the input of [action] — "the tap" — was sent. */
    fun verify(before: FlutterNavigationState, action: String): Verdict {
        if (before.confidence == Confidence.UNAVAILABLE) {
            return inconclusive(
                "$expected was not checked: the route before $action could not be read. ${before.unavailable}",
            )
        }
        val heldBefore = expectation.matches(before.current?.name)
        val started = clock()
        val deadline = started + expectation.timeoutMs
        var reads = 0
        var last: FlutterNavigationState
        while (true) {
            if (signal.isCancelled()) return cancelled(reads, started)
            last = read(false).also { reads++ }
            if (readable(last) && expectation.matches(last.current?.name)) {
                return satisfied(heldBefore, before, reads, started, action)
            }
            val left = deadline - clock()
            if (left <= 0) break
            if (!pause(minOf(POLL_MS, left))) return cancelled(reads, started)
        }
        return timedOut(Wait(before, heldBefore, last, reads, started), action)
    }

    /** Where a [verify] got to when its time ran out. */
    private class Wait(
        val before: FlutterNavigationState,
        val heldBefore: Boolean,
        val last: FlutterNavigationState,
        val reads: Int,
        val started: Long,
    )

    /** No keyed read matched in time. */
    private fun timedOut(wait: Wait, action: String): Verdict {
        val waited = "waited ${WaitReport.seconds(expectation.timeoutMs.toLong())} s after $action for $expected"
        if (!readable(wait.last)) {
            return inconclusive("$waited, and the route could not be read: ${reason(wait.last)}")
        }
        // A keyed read cannot see a navigator without a key: only the check can say the route is not there.
        val checked = read(true)
        val reads = wait.reads + 1
        return when {
            readable(checked) && expectation.matches(checked.current?.name) ->
                satisfied(wait.heldBefore, wait.before, reads, wait.started, action)
            readable(checked) && checked.confidence == Confidence.EXACT -> Verdict(
                ActionOutcome.NOT_OBSERVED,
                "NOT OBSERVED: $waited and did not see it; it is '${label(checked)}' (exact: every navigator " +
                    "mounted in the app was read). ${took(reads, wait.started)}. The input may still have landed, " +
                    "or the app may need longer: check before acting again.",
            )
            else -> inconclusive(
                "$waited and did not see it; the route showing is '${label(wait.last)}' as far as Spock can see, " +
                    "but ${reason(checked)}",
            )
        }
    }

    private fun satisfied(
        heldBefore: Boolean,
        before: FlutterNavigationState,
        reads: Int,
        started: Long,
        action: String,
    ): Verdict = if (heldBefore) {
        inconclusive(
            "$expected was already current before $action, so seeing it after (${took(reads, started)}) proves " +
                "nothing about $action. Expect a route $action changes.",
        )
    } else {
        Verdict(
            ActionOutcome.VERIFIED,
            "VERIFIED: $expected is current after ${took(reads, started)}. Before $action it was " +
                "'${label(before)}'.",
        )
    }

    private fun inconclusive(text: String) = Verdict(
        ActionOutcome.INCONCLUSIVE,
        "INCONCLUSIVE: $text Check the destination by what it shows instead, with expectText or " +
            "android_wait_for_element.",
    )

    private fun cancelled(reads: Int, started: Long) = Verdict(
        ActionOutcome.CANCELLED,
        "CANCELLED while checking for $expected, after ${took(reads, started)}. Whether it happened was not " +
            "verified.",
    )

    /** False when cancelled during the pause. */
    private fun pause(millis: Long): Boolean {
        try {
            sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return !signal.isCancelled()
    }

    private fun took(reads: Int, started: Long) =
        "$reads route read(s), ${WaitReport.seconds(clock() - started)} s"

    private companion object {
        const val POLL_MS = 500L
        const val RETRY_MS = 300L
        const val BEFORE_RETRIES = 3

        /** Read, and the current route is the one showing: no several navigators to choose between. */
        fun readable(state: FlutterNavigationState) =
            state.confidence != Confidence.UNAVAILABLE && state.current != null && state.undecided <= 1

        fun label(state: FlutterNavigationState): String = state.current?.let(FlutterRouteText::label) ?: "none"

        fun reason(state: FlutterNavigationState): String = when {
            state.confidence == Confidence.UNAVAILABLE -> state.unavailable.orEmpty()
            state.undecided > 1 ->
                "${state.undecided} navigators inside '${label(state)}' are not hidden, so Spock can't tell which " +
                    "one is showing."
            else -> "no route is showing."
        }
    }
}
