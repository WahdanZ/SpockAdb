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
    /**
     * Keyed `StatefulElement`s a read opens to look for a `NavigatorState`, one `getObject` each:
     * about 11 ms a call measured on an emulator, so 80 fit Home's 3 s budget with room for the
     * stack and a slower phone. Ones already known not to be navigators are not opened again.
     */
    val maxElements: Int = 80,
    /** `NavigatorState` instances counted by the heap walk. */
    val maxInstances: Int = 20,
)

/**
 * Reads a Flutter app's Navigator stack from its live objects, through [objects] alone (spike S25,
 * proven on Flutter 3.22.2 and 3.47.5 with the same field names).
 *
 * Every Flutter-private name Spock depends on is here and nowhere else, so a Flutter version that
 * moves one shows up as "unavailable" naming the field, never as a wrong stack. A field counts only
 * when the VM says Flutter's class declares it, so an app subclass's same-named private field
 * cannot stand in for it; a class counts as `NavigatorState` only from `navigator.dart`:
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
    private val notNavigators: NonNavigatorElements = NonNavigatorElements(),
) {

    /** Where Flutter declares a field Spock reads. */
    private class Owner(val className: String, val library: String)

    /** A `StatefulElement` in the key registry, and the identities that let a later read skip it. */
    private class KeyedElement(val element: JsonObject, val key: Long?, val identity: Long?) {
        fun knownIn(memory: NonNavigatorElements) = key != null && identity != null && memory.contains(key, identity)

        fun rememberIn(memory: NonNavigatorElements) {
            if (key != null && identity != null) memory.add(key, identity)
        }
    }

    /** A mounted `NavigatorState`, opened, and its class for the heap walk. */
    private class Navigator(val state: JsonObject, val classId: String)

    /** A stack as read, with each `_RouteEntry`'s identity, bottom to top, to tell it was not changed. */
    private class Stack(val state: FlutterNavigationState, val entries: List<Long?>)

    /** Why a read stopped, in words fit to show. */
    private class Unreadable(val reason: String, val transient: Boolean = false) : Exception(reason)

    /** One read's opened objects: an enum value, opened once per entry, is one call. */
    private val opened = HashMap<String, JsonObject>()

    /**
     * The stack of the one navigator with a `GlobalKey`: [Confidence.KEYED] at best. Plain reads, no
     * heap walk: a few to reach the key registry, one per keyed `StatefulElement` not yet known not
     * to be a navigator (all of them the first time, 60 or more in a small app; a screen's new ones
     * after), and four or five per route. For Home, on every refresh.
     */
    fun readKeyed(): FlutterNavigationState = guarded { keyedState(keyedNavigators()) }

    /**
     * [readKeyed], then one `getInstances` to count the mounted navigators: [Confidence.EXACT]
     * when the keyed one is the only one. The heap walk pauses the app (120–364 ms measured on
     * 3.47.5), so this runs on request only.
     *
     * The stack and the count are read at different moments, so EXACT also needs the one mounted
     * navigator to be the keyed one (by `identityHashCode`: object ids are not stable between
     * calls) and the stack, read again after the walk, to hold the same entries, all still idle.
     * A push between the two halves, or `runApp` replacing the navigator, is a change, not a stack.
     */
    fun readChecked(): FlutterNavigationState = guarded {
        val keyed = keyedNavigators()
        if (keyed.size != 1) return@guarded keyedState(keyed)
        val navigator = keyed.single()
        val stack = stackOf(navigator.state)
        if (stack.state.confidence != Confidence.KEYED) return@guarded stack.state
        val started = clock()
        val found = objects.instances(navigator.classId, limits.maxInstances)
        val heapWalkMs = clock() - started
        val mounted = mountedAmong(found)
        val same = mounted?.singleOrNull()?.let { only ->
            identity(only) == identity(navigator.state) && unchanged(navigator, stack)
        } ?: true
        NavigationConfidence.checked(stack.state, mounted?.size, heapWalkMs, limits.maxInstances, same)
    }

    private fun keyedState(navigators: List<Navigator>): FlutterNavigationState = when (navigators.size) {
        0 -> FlutterNavigationState.unavailable(NO_KEYED_NAVIGATOR)
        1 -> stackOf(navigators.single().state).state
        else -> FlutterNavigationState.unavailable(severalKeyed(navigators.size))
    }

    /** Whether [navigator], opened afresh, is still mounted and holds the same entries as [before]. */
    private fun unchanged(navigator: Navigator, before: Stack): Boolean {
        opened.clear()
        val state = open(navigator.state)
        if (!isMounted(state)) return false
        val after = stackOf(state)
        return after.state.stack == before.state.stack && after.entries == before.entries &&
            before.entries.none { it == null }
    }

    // ---------------------------------------------------------------- finding the navigator

    private fun keyedNavigators(): List<Navigator> {
        val owner = open(binding().field("_buildOwner", BINDING))
        val registry = open(owner.field("_globalKeyRegistry", BUILD_OWNER))
        val associations = registry.array("associations")
            ?: fail(missing("BuildOwner._globalKeyRegistry", "associations"))
        checkWhole(registry, associations, "the GlobalKey registry")
        // Every StatefulElement is opened, whatever its key's class: a Navigator takes any
        // GlobalKey<NavigatorState>, and at runtime that is the same LabeledGlobalKey an overlay's is.
        val unknown = associations.map { association ->
            val element = association.asJsonObject.obj("value") ?: fail(missing("MapAssociation", "value"))
            if (element.className == null) fail(missing("Element", "class"))
            KeyedElement(element, association.asJsonObject.obj("key")?.let(::identityOrNull), identityOrNull(element))
        }.filter { it.element.className == STATEFUL_ELEMENT && !it.knownIn(notNavigators) }
        val navigators = unknown.take(limits.maxElements).mapNotNull { keyed ->
            val state = open(keyed.element).field("_state", ELEMENT)
            val classId = navigatorClass(state)
            if (classId == null) keyed.rememberIn(notNavigators)
            classId?.let { open(state).takeIf(::isMounted)?.let { Navigator(it, classId) } }
        }
        // What was opened is remembered, so reading again goes on from there.
        if (unknown.size > limits.maxElements) fail(tooManyElements(unknown.size), transient = true)
        return navigators
    }

    /** Follow the VM class hierarchy rather than guessing from an app's subclass name. */
    private fun navigatorClass(state: JsonObject): String? {
        var type = state.obj("class") ?: fail(missing(STATEFUL_ELEMENT, "state class"))
        val visited = HashSet<String>()
        while (true) {
            val id = type.string("id") ?: fail(missing("Class", "id"))
            if (!visited.add(id)) fail("The VM returned a cyclic class hierarchy.")
            if (type.string("name") == NAVIGATOR_STATE && libraryOf(type) == NAVIGATOR_LIBRARY) return id
            if (type.string("name") == "Object") return null
            type = open(type).obj("super") ?: fail(missing("Class", "super"))
            if (type.isNull) return null
        }
    }

    /** A class's library uri: on the reference when the VM sends it, else on the class. */
    private fun libraryOf(type: JsonObject): String =
        (type.obj("library") ?: open(type).obj("library"))?.string("uri") ?: fail(missing("Class", "library"))

    private fun isMounted(state: JsonObject): Boolean {
        val element = state.field("_element", STATE, NAVIGATOR_STATE)
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

    /** The heap walk's mounted navigators, opened; null when it found more than it returned. */
    private fun mountedAmong(found: JsonObject): List<JsonObject>? {
        val refs = found.array("instances") ?: fail(missing("InstanceSet", "instances"))
        if (refs.any { it !is JsonObject }) fail("The VM returned an unreadable navigator instance.")
        val instances = refs.objects()
        val total = found.int("totalCount") ?: fail(missing("InstanceSet", "totalCount"))
        if (total > instances.size) return null
        return instances.map {
            opened.remove(it.string("id"))
            open(it)
        }.filter(::isMounted)
    }

    /** An instance's `identityHashCode`: the same object answers the same one on every call. */
    private fun identity(instance: JsonObject): Long = identityOrNull(instance) ?: fail(NO_IDENTITY)

    // ---------------------------------------------------------------- the stack

    private fun stackOf(navigator: JsonObject): Stack {
        val history = navigator.field("_history", NAVIGATOR)
        // `_History` since Flutter 3.7; a plain list before, read the same way.
        val list = if (history.string("kind") == LIST) {
            open(history)
        } else {
            open(open(history).field("_value", HISTORY))
        }
        val entries = list.array("elements") ?: fail(missing("_History._value", "elements"))
        checkWhole(list, entries, "the navigator's history")
        if (entries.size() == 0) return Stack(FlutterNavigationState.unavailable(NO_ROUTES), emptyList())
        val routes = entries.objects().map { ref ->
            val entry = open(ref)
            val routeRef = entry.field("route", ENTRY)
            val routeClass = routeRef.className ?: fail(missing(ROUTE_ENTRY, "route class"))
            val lifecycle = lifecycleOf(entry.field("currentState", ENTRY))
            if (lifecycle != IDLE) fail(inProgress(routeClass, lifecycle), transient = true)
            FlutterRoute(nameOf(open(routeRef), routeClass), routeClass)
        }
        return Stack(FlutterNavigationState(routes, Confidence.KEYED), entries.objects().map(::identityOrNull))
    }

    /** `_RouteLifecycle`'s value by name: `idle`, `pushing`… */
    private fun lifecycleOf(ref: JsonObject): String = stringOf(open(ref).field("_name", ENUM, ROUTE_LIFECYCLE))
        ?: fail(missing(ROUTE_LIFECYCLE, "_name"))

    /** `RouteSettings.name`; null when the route has none. */
    private fun nameOf(route: JsonObject, routeClass: String): String? {
        val settings = route.field("_settings", ROUTE, routeClass)
        if (!isInstance(settings)) fail(missing(routeClass, "_settings"))
        return stringOf(open(settings).field("name", ROUTE_SETTINGS))
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

    /**
     * The value of the field [name] that [owner] declares; a field the object does not have is
     * unavailable, by name, as a field of [shownAs].
     */
    private fun JsonObject.field(name: String, owner: Owner, shownAs: String = owner.className): JsonObject =
        array("fields").objects().firstOrNull { fieldName(it) == name && declaredBy(it, owner) }?.obj("value")
            ?: fail(missing(shownAs, name))

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
        const val NAVIGATOR_LIBRARY = "package:flutter/src/widgets/navigator.dart"
        private const val FRAMEWORK_LIBRARY = "package:flutter/src/widgets/framework.dart"
        const val NAVIGATOR_STATE = "NavigatorState"
        private const val WIDGETS_BINDING = "WidgetsBinding"
        private const val STATEFUL_ELEMENT = "StatefulElement"
        private const val ROUTE_ENTRY = "_RouteEntry"
        private const val ROUTE_LIFECYCLE = "_RouteLifecycle"
        private const val IDLE = "idle"
        private const val LIST = "List"
        private const val STRING = "String"
        private const val SENTINEL = "Sentinel"

        private val BINDING = Owner(WIDGETS_BINDING, BINDING_LIBRARY)
        private val BUILD_OWNER = Owner("BuildOwner", FRAMEWORK_LIBRARY)
        private val ELEMENT = Owner(STATEFUL_ELEMENT, FRAMEWORK_LIBRARY)
        private val STATE = Owner("State", FRAMEWORK_LIBRARY)
        private val NAVIGATOR = Owner(NAVIGATOR_STATE, NAVIGATOR_LIBRARY)
        private val HISTORY = Owner("_History", NAVIGATOR_LIBRARY)
        private val ENTRY = Owner(ROUTE_ENTRY, NAVIGATOR_LIBRARY)
        private val ROUTE = Owner("Route", NAVIGATOR_LIBRARY)
        private val ROUTE_SETTINGS = Owner("RouteSettings", NAVIGATOR_LIBRARY)

        /** Every enum's `_name` is `_Enum`'s, in `dart:core`. */
        private val ENUM = Owner("_Enum", "dart:core")

        const val NO_ROUTES = "The navigator has no routes yet."

        const val NO_IDENTITY = "The VM sent no identityHashCode for a navigator, so Spock cannot tell the one " +
            "it counted is the one it read."

        const val NO_KEYED_NAVIGATOR = "No navigator with a GlobalKey is mounted. MaterialApp and WidgetsApp key " +
            "their own; a Navigator an app builds without a key cannot be found."

        fun severalKeyed(count: Int) = "$count navigators have a GlobalKey — a nested Navigator, or a router's " +
            "shell — and Spock can't tell yet which one is showing."

        fun tooManyElements(count: Int) = "Too many keyed widgets to check ($count) in one read; Spock goes on " +
            "with the rest when it reads again."

        fun inProgress(routeClass: String, lifecycle: String) =
            "Navigation in progress: a $routeClass is $lifecycle. Read again once it settles."

        /** How a Flutter version that moved a field shows up. */
        fun missing(owner: String, name: String) =
            "$owner has no $name in this Flutter version, so Spock cannot read the stack there."

        /** A `BoundField`'s name: `decl.name`, or `name` on newer VMs. */
        private fun fieldName(field: JsonObject): String? = field.obj("decl")?.string("name") ?: field.string("name")

        /**
         * Whether [field] is [owner]'s, as far as the VM says: a reply that names no owner is taken
         * at its name. A mixin's field is declared by the mixin application the VM builds for it
         * (`…&WidgetsBinding`), which only the compiler can name, in whichever library applies it.
         */
        private fun declaredBy(field: JsonObject, owner: Owner): Boolean {
            val declared = field.obj("decl")?.obj("owner") ?: return true
            val name = declared.string("name")
            if (name != null && name.endsWith("&${owner.className}")) return true
            val library = declared.obj("library")?.string("uri")
            return (name == null || name == owner.className) && (library == null || library == owner.library)
        }

        private fun identityOrNull(instance: JsonObject): Long? = (instance.get("identityHashCode") as? JsonPrimitive)
            ?.takeIf { it.isNumber }?.asLong?.takeIf { it > 0 }

        private fun isInstance(ref: JsonObject): Boolean =
            ref.string("type")?.removePrefix("@") == "Instance" && ref.string("id") != null
    }
}

/** What a read's [Confidence] is, apart from the reads, so it can be tested on its own. */
internal object NavigationConfidence {

    const val NESTED_UNKEYED = "A navigator without a GlobalKey is mounted besides the keyed one — a nested " +
        "Navigator — and Spock can't tell which one is showing."

    const val NESTED_SEEN = "The last App back stack check found a nested navigator without a key, so " +
        "Spock can't tell which one is showing. Open App back stack to check again."

    const val CHANGED = "The navigation changed while Spock checked it; read again."

    fun tooManyInstances(limit: Int) = "More than $limit NavigatorState objects are in the app's heap; Spock " +
        "counts at most $limit."

    /**
     * The keyed read [keyed], with the heap walk's count of mounted navigators: exact when the keyed
     * one is the only one. [mounted] is null when the walk found more than it returned; [same] is
     * whether the one it found is the keyed navigator, its stack unchanged since [keyed] was read.
     */
    fun checked(
        keyed: FlutterNavigationState,
        mounted: Int?,
        heapWalkMs: Long,
        limit: Int = NavigatorLimits().maxInstances,
        same: Boolean = true,
    ): FlutterNavigationState = when {
        keyed.confidence != Confidence.KEYED -> keyed
        mounted == null -> FlutterNavigationState.unavailable(tooManyInstances(limit), heapWalkMs = heapWalkMs)
        mounted == 1 && same -> keyed.copy(confidence = Confidence.EXACT, heapWalkMs = heapWalkMs)
        mounted > 1 -> FlutterNavigationState.unavailable(NESTED_UNKEYED, heapWalkMs = heapWalkMs)
        // Another navigator, a changed stack, or none mounted where the keyed one was: the app moved on.
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
