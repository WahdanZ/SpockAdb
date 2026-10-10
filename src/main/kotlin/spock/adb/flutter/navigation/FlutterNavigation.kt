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
 * - [EXACT]: the navigator read is the only one mounted in the app, so its top route is what is
 *   showing. Proven by a heap walk (`getInstances`), which pauses the app briefly.
 * - [KEYED]: the only navigator with a `GlobalKey` — the app's root one — read exactly; a nested
 *   navigator without a key would not be seen.
 * - [UNAVAILABLE]: Spock cannot prove which stack is showing; [FlutterNavigationState.unavailable]
 *   says why. Nothing is guessed in its place.
 */
enum class Confidence { EXACT, KEYED, UNAVAILABLE }

/**
 * A Navigator's stack, bottom (the root route) to top (the current one), read in one pass from the
 * app's own `NavigatorState`. Never rebuilt from `Flutter.Navigation` events: they carry no push
 * or pop flag, and declarative navigation posts none.
 *
 * [current] is the top of [stack], so Home and the back stack can never disagree.
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
) {
    val current: FlutterRoute? get() = stack.lastOrNull()

    companion object {
        fun unavailable(reason: String, transient: Boolean = false, heapWalkMs: Long? = null) =
            FlutterNavigationState(emptyList(), Confidence.UNAVAILABLE, reason, transient, heapWalkMs)
    }
}
