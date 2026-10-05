package spock.adb.home

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import spock.adb.device.ConnectedDevice
import spock.adb.flutter.FlutterEvent
import spock.adb.flutter.FlutterSession
import spock.adb.flutter.FlutterSessionChange
import spock.adb.flutter.FlutterSessionListener
import spock.adb.flutter.FlutterSessionService
import spock.adb.flutter.SessionState
import spock.adb.flutter.analysis.FlutterExtensionEvent
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.flutter.navigation.NavigationConfidence
import spock.adb.flutter.navigation.NavigatorReader
import spock.adb.flutter.navigation.SessionObjects

/**
 * The Flutter half of "This screen": the selected Flutter app's Navigator, read from the session
 * Spock already has with it.
 *
 * It never attaches: with no session connected for the selected app on the selected device there
 * is nothing to read, and Home shows no Flutter row. It tells Home when the stack may have changed
 * — a live `Flutter.Navigation` event, a hot restart, the session coming or going — and uses those
 * events only as that signal: what Home shows is always read from the app.
 */
internal class FlutterRouteSource(project: Project, parent: Disposable) {

    /** Called on the EDT when the app may have navigated. */
    var onChange: () -> Unit = {}

    private val sessions = FlutterSessionService.getInstance(project)
    private val nested = NestedNavigatorMemory()

    @Volatile
    private var watched: FlutterSession? = null

    @Volatile
    private var disposed = false

    private val listener = object : FlutterSessionListener {
        override fun onEvent(event: FlutterEvent) {
            if (rereadsOn(event)) changed()
        }

        override fun onStateChanged(state: SessionState) = changed()
    }

    init {
        Disposer.register(parent) {
            disposed = true
            watched?.removeListener(listener)
        }
        sessions.addListener(parent) { change ->
            watch(
                when (change) {
                    is FlutterSessionChange.Connected -> change.session
                    is FlutterSessionChange.Replaced -> change.session
                    is FlutterSessionChange.Disconnected -> null
                },
            )
            changed()
        }
        watch(sessions.current)
    }

    /**
     * The session to read for [applicationId] on [device]: connected, verified as that app's
     * process on that device, and current. Null otherwise — Home then shows no Flutter row.
     */
    fun liveSession(device: ConnectedDevice?, applicationId: String?): FlutterSession? {
        if (disposed) return null
        val session = sessions.current ?: return null
        val identity = sessions.identity ?: return null
        val same = device != null && identity.verified && identity.serial == device.serialNumber &&
            identity.applicationId == applicationId
        return session.takeIf { same && it.state !is SessionState.Disconnected }
    }

    /**
     * Reads [session]'s stack on a pooled thread and hands it to [answer] on the EDT. With [check]
     * the read also counts the mounted navigators — a heap walk that pauses the app briefly — and
     * may say [spock.adb.flutter.navigation.Confidence.EXACT].
     */
    fun read(session: FlutterSession, check: Boolean, answer: (FlutterNavigationState) -> Unit) {
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val state = readNow(session, check)
            application.invokeLater({ answer(state) }) { disposed }
        }
    }

    /** Blocking. */
    private fun readNow(session: FlutterSession, check: Boolean): FlutterNavigationState {
        val snapshot = session.snapshot
        val state = snapshot.state
        if (state is SessionState.Paused) return FlutterNavigationState.unavailable(state.message)
        val isolate = snapshot.uiIsolateId
            ?: return FlutterNavigationState.unavailable("The session has no Flutter UI isolate selected yet.")
        val budget = if (check) CHECK_BUDGET_MS else HOME_BUDGET_MS
        val reader = NavigatorReader(SessionObjects(session, isolate, budget))
        if (!check) return NavigationConfidence.home(reader.readKeyed(), nested.seen(session, isolate))
        return reader.readChecked().also { nested.record(session, isolate, it) }
    }

    private fun watch(session: FlutterSession?) {
        val previous = watched
        if (previous === session) return
        previous?.removeListener(listener)
        watched = session
        if (!disposed) session?.addListener(listener)
    }

    private fun changed() {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater({ onChange() }) { disposed }
    }

    companion object {
        /** Home's read, on every refresh: plain object reads, about twenty for a short stack. */
        const val HOME_BUDGET_MS = 3_000L

        /** The back stack check: Home's read and a heap walk (120–364 ms measured on 3.47.5). */
        const val CHECK_BUDGET_MS = 5_000L

        /**
         * Whether [event] may mean another route is showing: a live `Flutter.Navigation`. DDS's
         * replay of earlier navigation is not, and neither is an isolate starting — `compute()`
         * starts them all the time; a hot restart reaches [FlutterSessionListener.onStateChanged]
         * as the session moves to the new UI isolate.
         */
        fun rereadsOn(event: FlutterEvent): Boolean =
            event.extensionKind == FlutterExtensionEvent.NAVIGATION && !event.history
    }
}

/**
 * Where the last back stack check found an unkeyed nested navigator, by session and isolate, so
 * Home stops showing the root's route as if it were what is showing. A check that proves one
 * navigator forgets it; a hot restart (a new isolate) or another session starts clean.
 */
internal class NestedNavigatorMemory {

    @Volatile
    private var seenIn: Pair<Any, String>? = null

    fun seen(session: Any, isolateId: String): Boolean = seenIn == (session to isolateId)

    fun record(session: Any, isolateId: String, checked: FlutterNavigationState) {
        when (checked.unavailable) {
            NavigationConfidence.NESTED_UNKEYED -> seenIn = session to isolateId
            null -> if (seen(session, isolateId)) seenIn = null
        }
    }
}
