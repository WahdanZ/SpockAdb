package spock.adb.home

import spock.adb.ApplicationBackStack
import spock.adb.flutter.navigation.Confidence
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.flutter.navigation.FlutterRoute

/** What Home and App back stack say about a Flutter app's navigation, apart from Swing. */
internal object FlutterRouteText {

    const val UNAVAILABLE = "unavailable"
    const val ROOT = "root"
    const val CURRENT = "current"
    const val HIDDEN = "hidden"
    const val SHOWING = "showing"
    const val NESTED = "Nested navigator"
    const val ACTIVITIES = "Activities"
    const val FLUTTER_ROUTES = "Flutter routes"

    /** The Flutter half of App Back Stack when the session it read is no longer the app's. */
    const val SESSION_CHANGED = "The Flutter session changed while Spock read it; open App back stack again."

    /**
     * One line of the back stack, bottom first; [mark] says `root`, `current`, or both. A [header]
     * line opens a nested navigator, marked `hidden` or `showing`; [depth] is how far it is nested.
     */
    data class Row(
        val text: String,
        val unnamed: Boolean,
        val mark: String?,
        val depth: Int = 0,
        val header: Boolean = false,
    )

    /** `/item/3`; a route with no name by its class — `DialogRoute (unnamed)` — never an invented path. */
    fun label(route: FlutterRoute): String = route.name ?: "${route.routeClass} (unnamed)"

    /** Home's value: the current route, or `unavailable`. */
    fun home(state: FlutterNavigationState): String = state.current?.let(::label) ?: UNAVAILABLE

    fun homeTooltip(state: FlutterNavigationState): String = when {
        state.confidence == Confidence.UNAVAILABLE -> state.unavailable.orEmpty()
        state.nested.isNotEmpty() -> routeClass(state) + nestedCurrent(state) + when (state.confidence) {
            Confidence.EXACT -> " Every navigator mounted in the app was read."
            else -> " A navigator without a key would not be seen: App back stack checks for one."
        }
        state.confidence == Confidence.EXACT -> "${routeClass(state)}The current route of the app's only mounted " +
            "navigator, read from the app."
        else -> "${routeClass(state)}The current route of the app's root navigator, read from the app. A nested " +
            "navigator without a key would not be seen: App back stack checks for one."
    }

    /**
     * The stack bottom (root) to top (current), as the popup lists it. A route holding nested
     * navigators is followed by each of them, a header line and then its own routes, one level in.
     */
    fun rows(state: FlutterNavigationState): List<Row> {
        val current = state.currentPath.lastOrNull()
        val onPath = state.currentPath.map { it.navigator }.toSet()
        val rows = mutableListOf<Row>()
        fun add(navigator: Int, depth: Int) {
            state.routesOf(navigator).forEachIndexed { index, route ->
                val marks = listOfNotNull(
                    ROOT.takeIf { navigator == FlutterNavigationState.ROOT && index == 0 },
                    CURRENT.takeIf { current == FlutterNavigationState.Step(navigator, index) },
                )
                rows += Row(label(route), route.name == null, marks.joinToString(" · ").ifEmpty { null }, depth)
                state.inside(navigator, index).forEach { child ->
                    val mark = when {
                        state.nested[child].hidden -> HIDDEN
                        child in onPath -> SHOWING
                        else -> null
                    }
                    rows += Row(NESTED, unnamed = false, mark = mark, depth = depth + 1, header = true)
                    add(child, depth + 1)
                }
            }
        }
        add(FlutterNavigationState.ROOT, 0)
        return rows
    }

    /** Under the list: how sure Spock is, or why there is no list, and what the check cost the app. */
    fun footer(state: FlutterNavigationState): String {
        val count = state.navigatorCount
        val verdict = when {
            state.confidence == Confidence.UNAVAILABLE -> state.unavailable.orEmpty()
            state.nested.isEmpty() && state.confidence == Confidence.EXACT ->
                "Exact: this is the only navigator mounted in the app."
            state.nested.isEmpty() -> "The app's root navigator. A nested navigator without a key would not be seen."
            state.confidence == Confidence.EXACT ->
                "Exact: these $count navigators are the only ones mounted in the app.${undecidedNote(state)}"
            else ->
                "The app's $count navigators with a GlobalKey. A navigator without a key would not be seen." +
                    undecidedNote(state)
        }
        val pause = state.heapWalkMs?.let { " The navigator check took about $it ms and briefly paused the app." }
        return verdict + pause.orEmpty()
    }

    /** App Back Stack's title: the app read, else the app selected. */
    fun title(activities: Result<ApplicationBackStack>, selected: String?): String =
        listOfNotNull("App Back Stack", activities.getOrNull()?.applicationId ?: selected).joinToString(" · ")

    /** Under Activities when there is no list: none running, or why they could not be read. */
    fun activitiesNote(activities: Result<ApplicationBackStack>): String? = activities.fold(
        onSuccess = { stack ->
            "No activities of ${stack.applicationId} are running.".takeIf { stack.activities.isEmpty() }
        },
        onFailure = { error ->
            "Could not read the activities: ${error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName}"
        },
    )

    /** Home's tooltip for an app with nested navigators: where the current route is. */
    private fun nestedCurrent(state: FlutterNavigationState): String = if (state.undecided > 1) {
        "${state.undecided} navigators inside this route are not hidden, so Spock can't tell which one is " +
            "showing, and names the route that holds them."
    } else {
        val depth = state.currentPath.size - 1
        "The current route, read from the app, followed through $depth nested " +
            (if (depth == 1) "navigator" else "navigators") + " to the one showing."
    }

    private fun undecidedNote(state: FlutterNavigationState): String = if (state.undecided > 1) {
        " ${state.undecided} navigators inside ${state.current?.let(::label)} are not hidden, so Spock can't tell " +
            "which one is showing; current stops at the route that holds them."
    } else {
        ""
    }

    private fun routeClass(state: FlutterNavigationState): String =
        state.current?.takeIf { it.name != null }?.let { "${it.routeClass}. " }.orEmpty()
}
