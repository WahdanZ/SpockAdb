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
    const val ACTIVITIES = "Activities"
    const val FLUTTER_ROUTES = "Flutter routes"

    /** The Flutter half of App Back Stack when the session it read is no longer the app's. */
    const val SESSION_CHANGED = "The Flutter session changed while Spock read it; open App back stack again."

    /** One line of the back stack, bottom first; [mark] says `root`, `current`, or both. */
    data class Row(val text: String, val unnamed: Boolean, val mark: String?)

    /** `/item/3`; a route with no name by its class — `DialogRoute (unnamed)` — never an invented path. */
    fun label(route: FlutterRoute): String = route.name ?: "${route.routeClass} (unnamed)"

    /** Home's value: the current route, or `unavailable`. */
    fun home(state: FlutterNavigationState): String = state.current?.let(::label) ?: UNAVAILABLE

    fun homeTooltip(state: FlutterNavigationState): String = when (state.confidence) {
        Confidence.EXACT -> "${routeClass(state)}The current route of the app's only mounted navigator, read " +
            "from the app."
        Confidence.KEYED -> "${routeClass(state)}The current route of the app's root navigator, read from the " +
            "app. A nested navigator without a key would not be seen: App back stack checks for one."
        Confidence.UNAVAILABLE -> state.unavailable.orEmpty()
    }

    /** The stack bottom (root) to top (current), as the popup lists it. */
    fun rows(state: FlutterNavigationState): List<Row> = state.stack.mapIndexed { index, route ->
        val marks = listOfNotNull(ROOT.takeIf { index == 0 }, CURRENT.takeIf { index == state.stack.lastIndex })
        Row(label(route), route.name == null, marks.joinToString(" · ").ifEmpty { null })
    }

    /** Under the list: how sure Spock is, or why there is no list, and what the check cost the app. */
    fun footer(state: FlutterNavigationState): String {
        val verdict = when (state.confidence) {
            Confidence.EXACT -> "Exact: this is the only navigator mounted in the app."
            Confidence.KEYED -> "The app's root navigator. A nested navigator without a key would not be seen."
            Confidence.UNAVAILABLE -> state.unavailable.orEmpty()
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

    private fun routeClass(state: FlutterNavigationState): String =
        state.current?.takeIf { it.name != null }?.let { "${it.routeClass}. " }.orEmpty()
}
