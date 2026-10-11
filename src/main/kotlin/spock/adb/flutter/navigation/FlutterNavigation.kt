package spock.adb.flutter.navigation

/**
 * One route on a Flutter Navigator's stack, as the app holds it.
 *
 * [name] is the route's `RouteSettings.name` (a `Page`'s name for `Navigator.pages`), null when
 * the route has none — a dialog, an unnamed `MaterialPageRoute`. [routeClass] is the route's own
 * class (`MaterialPageRoute`, `DialogRoute`), shown in place of a name rather than inventing one.
 */
data class FlutterRoute(val name: String?, val routeClass: String)

/**
 * How far Spock can vouch for a [FlutterNavigationState].
 *
 * - [EXACT]: the navigators read are the only ones mounted in the app. Proven by a heap walk
 *   (`getInstances`), which pauses the app briefly.
 * - [KEYED]: every navigator with a `GlobalKey` — the app's root one, a router's shells and tabs —
 *   read exactly; a navigator without a key would not be seen.
 * - [UNAVAILABLE]: Spock cannot prove which stack is showing; [FlutterNavigationState.unavailable]
 *   says why. Nothing is guessed in its place.
 */
enum class Confidence { EXACT, KEYED, UNAVAILABLE }

/**
 * A navigator inside a route of another one: a go_router shell's branch, an auto_route tab or
 * nested router, a `Navigator` an app builds in a page.
 *
 * [parent] is the navigator holding it — [FlutterNavigationState.ROOT], else an index into
 * [FlutterNavigationState.nested] — and [parentRoute] the index of the route in that navigator's
 * stack it sits in. [hidden] when something above it says it is not on screen: an inactive tab
 * (`IndexedStack`'s `Visibility`, `Offstage` with `TickerMode` off), or a route covered by another.
 */
data class NestedNavigator(
    val stack: List<FlutterRoute>,
    val parent: Int,
    val parentRoute: Int,
    val hidden: Boolean,
)

/**
 * A Flutter app's navigation, read in one pass from its own `NavigatorState`s: [stack] is the root
 * navigator's, bottom (the root route) to top, and [nested] every navigator inside its routes,
 * outermost first. Never rebuilt from `Flutter.Navigation` events: they carry no push or pop flag,
 * and declarative navigation posts none.
 *
 * [current] follows the screen down: the top of the root's stack, then, inside that route, the top
 * of the one nested navigator not hidden, and so on. Where a route holds several navigators none of
 * which is hidden, Spock cannot tell which one is showing, and [current] stops at that route,
 * which is on screen either way; [undecided] says how many it could not choose between.
 */
data class FlutterNavigationState(
    val stack: List<FlutterRoute>,
    val confidence: Confidence,
    /** Why the stack cannot be shown, in words; null unless [confidence] is [Confidence.UNAVAILABLE]. */
    val unavailable: String? = null,
    /** A navigation was in progress: the same read a moment later may succeed. */
    val transient: Boolean = false,
    /** How long the heap walk that proved [Confidence.EXACT] (or disproved it) kept the app waiting. */
    val heapWalkMs: Long? = null,
    val nested: List<NestedNavigator> = emptyList(),
) {
    /** How many navigators were read: the root and every nested one. */
    val navigatorCount: Int get() = nested.size + 1

    /** One step of [currentPath]: a navigator ([ROOT] or an index into [nested]) and a route in it. */
    data class Step(val navigator: Int, val route: Int)

    /** The navigator's stack: the root's for [ROOT], else the nested one's. */
    fun routesOf(navigator: Int): List<FlutterRoute> = if (navigator == ROOT) stack else nested[navigator].stack

    /** The nested navigators inside route [route] of [navigator], in [nested] order. */
    fun inside(navigator: Int, route: Int): List<Int> =
        nested.indices.filter { nested[it].parent == navigator && nested[it].parentRoute == route }

    /** From the root's top route down to [current]. */
    val currentPath: List<Step> get() = walk().first

    val current: FlutterRoute? get() = currentPath.lastOrNull()?.let { routesOf(it.navigator)[it.route] }

    /** How many navigators [current]'s route holds that are not hidden, when more than one; else 0. */
    val undecided: Int get() = walk().second

    private fun walk(): Pair<List<Step>, Int> {
        val path = mutableListOf<Step>()
        var navigator = ROOT
        // Each nested navigator is visited at most once: the reader builds them as a tree.
        repeat(nested.size + 1) {
            val routes = routesOf(navigator)
            if (routes.isEmpty()) return path to 0
            val top = Step(navigator, routes.lastIndex)
            path += top
            val showing = inside(navigator, top.route).filterNot { nested[it].hidden }
            if (showing.size != 1) return path to (if (showing.size > 1) showing.size else 0)
            navigator = showing.single()
        }
        return path to 0
    }

    companion object {
        /** The root navigator, as a [NestedNavigator.parent]. */
        const val ROOT = -1

        fun unavailable(reason: String, transient: Boolean = false, heapWalkMs: Long? = null) =
            FlutterNavigationState(emptyList(), Confidence.UNAVAILABLE, reason, transient, heapWalkMs)
    }
}
