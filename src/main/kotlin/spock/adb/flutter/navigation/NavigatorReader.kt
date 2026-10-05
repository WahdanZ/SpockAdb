package spock.adb.flutter.navigation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import spock.adb.flutter.vmservice.Redaction
import spock.adb.flutter.vmservice.VmServiceException

/**
 * The three VM Service reads [NavigatorReader] makes, on one isolate: `getIsolate`, `getObject`
 * and `getInstances`. Nothing else can be asked through it — no `evaluate`, no `invoke` — so a
 * read of the stack runs no code in the app.
 */
internal interface VmObjects {
    /** `getIsolate`. */
    fun isolate(): JsonObject

    /** `getObject` on [objectId]. */
    fun get(objectId: String): JsonObject

    /** `getInstances` of [classId], at most [limit]: a heap walk, which pauses the app briefly. */
    fun instances(classId: String, limit: Int): JsonObject
}

/** How much a read may open before it gives up and says so. */
internal data class NavigatorLimits(
    /** `StatefulElement`s in the key registry opened to look for a `NavigatorState`. */
    val maxElements: Int = 200,
    /** `NavigatorState` instances counted by the heap walk. */
    val maxInstances: Int = 20,
)

/**
 * Reads a Flutter app's Navigator stack from its live objects, through [objects] alone (spike S25,
 * proven on Flutter 3.22.2 and 3.47.5 with the same field names).
 *
 * Every Flutter-private name Spock depends on is here and nowhere else, so a Flutter version that
 * moves one shows up as "unavailable" naming the field, never as a wrong stack:
 * - finding the navigator: `WidgetsBinding._instance` (static, `package:flutter/src/widgets/binding.dart`)
 *   → `_buildOwner` → `_globalKeyRegistry` (a `Map<GlobalKey, Element>`) → each `StatefulElement`'s
 *   `_state`, kept when its class is `NavigatorState` and it is mounted (`State._element` set).
 *   Only navigators with a `GlobalKey` are there — `MaterialApp`'s root one is (`GlobalObjectKey`).
 * - the stack: `NavigatorState._history` (a `_History`, or a plain list) → `_value`, bottom to top
 *   → each `_RouteEntry`'s `currentState` (`_RouteLifecycle`, by `_name`) and `route` → `_settings`
 *   (a `RouteSettings`, or the `Page` for `Navigator.pages`) → `name`.
 *
 * Exact only when every entry is `idle`. Any other lifecycle is a navigation under way — `pushing`
 * and `popping` last the transition — and the answer is unavailable, not a stack with an entry
 * dropped: Flutter's own `_RouteEntry.isPresent` counts `pop`, `complete` and `remove` as present
 * and `popping` (still on screen) as not, so no one reading of "present" matches what is showing.
 *
 * Blocking: from a pooled thread.
 */
internal class NavigatorReader(
    private val objects: VmObjects,
    private val limits: NavigatorLimits = NavigatorLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** A mounted `NavigatorState`, opened, and its class for the heap walk. */
    private class Navigator(val state: JsonObject, val classId: String)

    /** Why a read stopped, in words fit to show. */
    private class Unreadable(val reason: String, val transient: Boolean = false) : Exception(reason)

    /** One read's opened objects: an enum value, opened once per entry, is one call. */
    private val opened = HashMap<String, JsonObject>()

    /**
     * The stack of the one navigator with a `GlobalKey`: [Confidence.KEYED] at best. Plain reads,
     * about twenty `getObject` calls for a short stack; no heap walk. For Home, on every refresh.
     */
    fun readKeyed(): FlutterNavigationState = guarded { keyedState(keyedNavigators()) }

    /**
     * [readKeyed], then one `getInstances` to count the mounted navigators: [Confidence.EXACT]
     * when the keyed one is the only one. The heap walk pauses the app (120–364 ms measured on
     * 3.47.5), so this runs on request only.
     */
    fun readChecked(): FlutterNavigationState = guarded {
        val keyed = keyedNavigators()
        val state = keyedState(keyed)
        if (state.confidence != Confidence.KEYED) return@guarded state
        val started = clock()
        val found = objects.instances(keyed.single().classId, limits.maxInstances)
        val heapWalkMs = clock() - started
        NavigationConfidence.checked(state, mountedAmong(found), heapWalkMs, limits.maxInstances)
    }

    private fun keyedState(navigators: List<Navigator>): FlutterNavigationState = when (navigators.size) {
        0 -> FlutterNavigationState.unavailable(NO_KEYED_NAVIGATOR)
        1 -> stackOf(navigators.single().state)
        else -> FlutterNavigationState.unavailable(severalKeyed(navigators.size))
    }

    // ---------------------------------------------------------------- finding the navigator

    private fun keyedNavigators(): List<Navigator> {
        val owner = open(binding().field("_buildOwner", "WidgetsBinding"))
        val registry = open(owner.field("_globalKeyRegistry", "BuildOwner"))
        val associations = registry.array("associations")
            ?: fail(missing("BuildOwner._globalKeyRegistry", "associations"))
        checkWhole(registry, associations, "the GlobalKey registry")
        val elements = associations.map { association ->
            val element = association.asJsonObject.obj("value") ?: fail(missing("MapAssociation", "value"))
            if (element.className == null) fail(missing("Element", "class"))
            element
        }.filter { it.className == STATEFUL_ELEMENT }
        if (elements.size > limits.maxElements) fail(tooManyElements(limits.maxElements))
        return elements.mapNotNull { element ->
            val state = open(element).field("_state", STATEFUL_ELEMENT)
            val classId = navigatorClass(state) ?: return@mapNotNull null
            open(state).takeIf(::isMounted)?.let { Navigator(it, classId) }
        }
    }

    /** Follow the VM class hierarchy rather than guessing from an app's subclass name. */
    private fun navigatorClass(state: JsonObject): String? {
        var type = state.obj("class") ?: fail(missing(STATEFUL_ELEMENT, "state class"))
        val visited = HashSet<String>()
        while (true) {
            val id = type.string("id") ?: fail(missing("Class", "id"))
            if (!visited.add(id)) fail("The VM returned a cyclic class hierarchy.")
            if (type.string("name") == NAVIGATOR_STATE) return id
            if (type.string("name") == "Object") return null
            type = open(type).obj("super") ?: fail(missing("Class", "super"))
            if (type.isNull) return null
        }
    }

    private fun isMounted(state: JsonObject): Boolean {
        val element = state.field("_element", NAVIGATOR_STATE)
        if (element.isNull) return false
        if (!isInstance(element)) fail(missing(NAVIGATOR_STATE, "_element instance"))
        return true
    }

    /** `WidgetsBinding._instance`, opened. */
    private fun binding(): JsonObject {
        val library = objects.isolate().array("libraries").objects()
            .firstOrNull { it.string("uri") == BINDING_LIBRARY }
            ?: fail("The isolate has no $BINDING_LIBRARY: it is not a Flutter app's UI isolate.")
        val bindingClass = open(library).array("classes").objects().firstOrNull { it.string("name") == WIDGETS_BINDING }
            ?: fail(missing(BINDING_LIBRARY, WIDGETS_BINDING))
        val instanceField = open(bindingClass).array("fields").objects()
            .firstOrNull { it.string("name") == "_instance" }
            ?: fail(missing(WIDGETS_BINDING, "_instance"))
        val instance = open(instanceField).obj("staticValue")?.takeIf(::isInstance)
            ?: fail("WidgetsBinding._instance is not set yet: the app has not started its widgets.")
        return open(instance)
    }

    /** How many of the heap walk's navigators are mounted; null when it found more than it returned. */
    private fun mountedAmong(found: JsonObject): Int? {
        val refs = found.array("instances") ?: fail(missing("InstanceSet", "instances"))
        if (refs.any { it !is JsonObject }) fail("The VM returned an unreadable navigator instance.")
        val instances = refs.objects()
        val total = found.int("totalCount") ?: fail(missing("InstanceSet", "totalCount"))
        if (total > instances.size) return null
        return instances.count {
            opened.remove(it.string("id"))
            isMounted(open(it))
        }
    }

    // ---------------------------------------------------------------- the stack

    private fun stackOf(navigator: JsonObject): FlutterNavigationState {
        val history = navigator.field("_history", NAVIGATOR_STATE)
        // `_History` since Flutter 3.7; a plain list before, read the same way.
        val list = if (history.string("kind") == LIST) {
            open(history)
        } else {
            open(open(history).field("_value", "_History"))
        }
        val entries = list.array("elements") ?: fail(missing("_History._value", "elements"))
        checkWhole(list, entries, "the navigator's history")
        if (entries.size() == 0) return FlutterNavigationState.unavailable("The navigator has no routes yet.")
        val routes = entries.objects().map { ref ->
            val entry = open(ref)
            val routeRef = entry.field("route", ROUTE_ENTRY)
            val routeClass = routeRef.className ?: fail(missing(ROUTE_ENTRY, "route class"))
            val lifecycle = lifecycleOf(entry.field("currentState", ROUTE_ENTRY))
            if (lifecycle != IDLE) fail(inProgress(routeClass, lifecycle), transient = true)
            FlutterRoute(nameOf(open(routeRef), routeClass), routeClass)
        }
        return FlutterNavigationState(routes, Confidence.KEYED)
    }

    /** `_RouteLifecycle`'s value by name: `idle`, `pushing`… */
    private fun lifecycleOf(ref: JsonObject): String = stringOf(open(ref).field("_name", ROUTE_LIFECYCLE))
        ?: fail(missing(ROUTE_LIFECYCLE, "_name"))

    /** `RouteSettings.name`; null when the route has none. */
    private fun nameOf(route: JsonObject, routeClass: String): String? {
        val settings = route.field("_settings", routeClass)
        if (!isInstance(settings)) fail(missing(routeClass, "_settings"))
        return stringOf(open(settings).field("name", "RouteSettings"))
    }

    /** A `String`'s value, or null for `null`; a value the VM shortened is opened for the rest. */
    private fun stringOf(ref: JsonObject): String? = when {
        ref.isNull -> null
        ref.string("kind") != STRING -> fail("Expected a String or null for a route name or lifecycle.")
        ref.boolean("valueAsStringIsTruncated") -> open(ref).let { full ->
            if (full.boolean("valueAsStringIsTruncated")) fail("The VM shortened a route name.")
            full.string("valueAsString") ?: fail(missing("String", "valueAsString"))
        }
        else -> ref.string("valueAsString") ?: fail(missing("String", "valueAsString"))
    }

    // ---------------------------------------------------------------- objects

    private fun open(ref: JsonObject): JsonObject {
        val id = ref.string("id") ?: fail("An object Spock needs has no id.")
        opened[id]?.let { return it }
        val result = objects.get(id)
        if (result.string("type") == SENTINEL) fail("The app let go of an object while Spock read it; read again.")
        return result.also { opened[id] = it }
    }

    /** The value of the field [name]; a field the object does not have is unavailable, by name. */
    private fun JsonObject.field(name: String, owner: String): JsonObject =
        array("fields").objects().firstOrNull { fieldName(it) == name }?.obj("value") ?: fail(missing(owner, name))

    /** The VM lists the whole of a map or list unless told otherwise; a part of one proves nothing. */
    private fun checkWhole(collection: JsonObject, items: JsonArray, what: String) {
        val length = collection.int("length") ?: fail(missing(what, "length"))
        if (items.any { it !is JsonObject }) fail("The VM returned an unreadable entry in $what.")
        if (items.size() != length) fail("The VM returned ${items.size()} of the $length entries in $what.")
    }

    private fun guarded(read: () -> FlutterNavigationState): FlutterNavigationState = try {
        opened.clear()
        read()
    } catch (e: Unreadable) {
        FlutterNavigationState.unavailable(e.reason, e.transient)
    } catch (e: VmServiceException) {
        FlutterNavigationState.unavailable(Redaction.scrub("Could not read the navigator: ${e.message}"))
    }

    private fun fail(reason: String, transient: Boolean = false): Nothing = throw Unreadable(reason, transient)

    companion object {
        const val BINDING_LIBRARY = "package:flutter/src/widgets/binding.dart"
        const val NAVIGATOR_STATE = "NavigatorState"
        private const val WIDGETS_BINDING = "WidgetsBinding"
        private const val STATEFUL_ELEMENT = "StatefulElement"
        private const val ROUTE_ENTRY = "_RouteEntry"
        private const val ROUTE_LIFECYCLE = "_RouteLifecycle"
        private const val IDLE = "idle"
        private const val LIST = "List"
        private const val STRING = "String"
        private const val SENTINEL = "Sentinel"

        const val NO_KEYED_NAVIGATOR = "No navigator with a GlobalKey is mounted. MaterialApp and WidgetsApp key " +
            "their own; a Navigator an app builds without a key cannot be found."

        fun severalKeyed(count: Int) = "$count navigators have a GlobalKey — a nested Navigator, or a router's " +
            "shell — and Spock can't tell yet which one is showing."

        fun tooManyElements(limit: Int) = "The app has more than $limit keyed stateful widgets; Spock stops there."

        fun inProgress(routeClass: String, lifecycle: String) =
            "Navigation in progress: a $routeClass is $lifecycle. Read again once it settles."

        /** How a Flutter version that moved a field shows up. */
        fun missing(owner: String, name: String) =
            "$owner has no $name in this Flutter version, so Spock cannot read the stack there."

        /** A `BoundField`'s name: `decl.name`, or `name` on newer VMs. */
        private fun fieldName(field: JsonObject): String? = field.obj("decl")?.string("name") ?: field.string("name")

        private fun isInstance(ref: JsonObject): Boolean =
            ref.string("type")?.removePrefix("@") == "Instance" && ref.string("id") != null
    }
}

/** What a read's [Confidence] is, apart from the reads, so it can be tested on its own. */
internal object NavigationConfidence {

    const val NESTED_UNKEYED = "A navigator without a GlobalKey is mounted besides the keyed one — a nested " +
        "Navigator — and Spock can't tell which one is showing."

    const val NESTED_SEEN = "The last Flutter back stack check found a nested navigator without a key, so " +
        "Spock can't tell which one is showing. Open Flutter back stack to check again."

    const val CHANGED = "The app's navigators changed while Spock read them; read again."

    fun tooManyInstances(limit: Int) = "More than $limit NavigatorState objects are in the app's heap; Spock " +
        "counts at most $limit."

    /**
     * The keyed read [keyed], with the heap walk's count of mounted navigators: exact when the keyed
     * one is the only one. [mounted] is null when the walk found more than it returned.
     */
    fun checked(
        keyed: FlutterNavigationState,
        mounted: Int?,
        heapWalkMs: Long,
        limit: Int = NavigatorLimits().maxInstances,
    ): FlutterNavigationState = when {
        keyed.confidence != Confidence.KEYED -> keyed
        mounted == null -> FlutterNavigationState.unavailable(tooManyInstances(limit), heapWalkMs = heapWalkMs)
        mounted == 1 -> keyed.copy(confidence = Confidence.EXACT, heapWalkMs = heapWalkMs)
        mounted > 1 -> FlutterNavigationState.unavailable(NESTED_UNKEYED, heapWalkMs = heapWalkMs)
        // The keyed navigator was mounted a moment ago and the walk found none: the app moved on.
        else -> FlutterNavigationState.unavailable(CHANGED, transient = true, heapWalkMs = heapWalkMs)
    }

    /**
     * What Home shows for a keyed read: unavailable while the last check on this isolate found an
     * unkeyed nested navigator, since the root's top route may then not be what is showing.
     */
    fun home(read: FlutterNavigationState, nestedSeen: Boolean): FlutterNavigationState =
        if (nestedSeen && read.confidence == Confidence.KEYED) FlutterNavigationState.unavailable(NESTED_SEEN) else read
}

private val JsonObject.className: String? get() = obj("class")?.string("name")

private val JsonObject.isNull: Boolean get() = string("kind") == "Null"

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt

private fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeIf {
    it.isBoolean
}?.asBoolean == true

private fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
