package spock.adb.flutter.navigation

/**
 * The keyed `StatefulElement`s a read already opened and found holding no `NavigatorState`, so the
 * next read of the same isolate opens only the new ones: about one `getObject` each, and an app's
 * overlays, scaffolds and forms put dozens in the key registry.
 *
 * An element is known by the `identityHashCode` of its key and of itself, both in the registry's
 * reply: object ids are not stable between calls. An element's state never changes (`_state` is
 * final), and a widget whose key changes gets a new element, so a known pair stays a non-navigator.
 *
 * Thread-safe: Home's read and a back stack check may run at once.
 */
internal class NonNavigatorElements {

    private var scope: Pair<Any, String>? = null
    private val known = HashSet<Pair<Long, Long>>()

    /** Starts over for another session or isolate: a hot restart's elements are all new. */
    @Synchronized
    fun scopeTo(session: Any, isolateId: String): NonNavigatorElements = apply {
        val next = session to isolateId
        if (scope != next) {
            scope = next
            known.clear()
        }
    }

    @Synchronized
    fun contains(key: Long, element: Long): Boolean = (key to element) in known

    @Synchronized
    fun add(key: Long, element: Long) {
        // Elements come and go with screens; past this many, most are gone, so start over.
        if (known.size >= MAX_KNOWN) known.clear()
        known += key to element
    }

    private companion object {
        const val MAX_KNOWN = 4_096
    }
}
