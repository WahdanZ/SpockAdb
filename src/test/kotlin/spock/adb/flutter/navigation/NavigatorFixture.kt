package spock.adb.flutter.navigation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Builds a [FixtureObjects] app with several navigators in the object shapes spike S26 read from
 * Flutter 3.22.2 and 3.47.5 (go_router shells, auto_route tabs and nested routers): each
 * navigator's element holds an inherited-widget map naming the route it is inside and the
 * visibility scopes above it.
 *
 * ```
 * val app = NavigatorFixture()
 * val root = app.navigator("HomeTabsRoute")
 * app.navigator("FeedListRoute", inside = root to 0, visible = false)
 * ```
 */
class NavigatorFixture(
    /** Where `_VisibilityScope` is declared: `visibility.dart` on 3.22, `indexed_stack.dart` on 3.47. */
    private val visibilityLibrary: String = "package:flutter/src/widgets/visibility.dart",
) {

    private class Nav(
        val routes: List<String?>,
        val inside: Pair<Int, Int>?,
        val ticker: Boolean?,
        val visible: Boolean?,
        val keyed: Boolean,
        val lifecycle: String,
    )

    private val navs = mutableListOf<Nav>()
    private val objects = JsonObject()
    private var next = 10

    /** Navigator `index` and the id of its `NavigatorState`, once built. */
    val stateIds = mutableListOf<String>()

    /** The id of each navigator's element, once built. */
    val elementIds = mutableListOf<String>()

    /** The id of each navigator's `_ModalScopeStatus` widget, once built; null for one inside no route. */
    val statusWidgetIds = mutableListOf<String?>()

    /** The id of each navigator's `_VisibilityScope` widget, once built; null when it has none. */
    val visibilityWidgetIds = mutableListOf<String?>()

    /**
     * A navigator with [routes], bottom to top (a null name is an unnamed route), inside route
     * `inside.second` of navigator `inside.first`; null for the root. [ticker] and [visible] are the
     * nearest `TickerMode` and `Visibility` above it, absent when null.
     */
    fun navigator(
        vararg routes: String?,
        inside: Pair<Int, Int>? = null,
        ticker: Boolean? = null,
        visible: Boolean? = null,
        keyed: Boolean = true,
        lifecycle: String = "idle",
    ): Int {
        navs += Nav(routes.toList(), inside, ticker, visible, keyed, lifecycle)
        return navs.lastIndex
    }

    fun build(): FixtureObjects {
        val routeRefs = navs.map { nav -> nav.routes.map { name -> route(name) } }
        val registry = JsonArray()
        val heap = JsonArray()
        navs.forEachIndexed { index, nav ->
            val state = id()
            val element = id()
            stateIds += state
            elementIds += element
            val history = list(routeRefs[index].map { entry(it, nav.lifecycle) })
            val inherited = inheritedMap(nav, routeRefs)
            put(element, "StatefulElement", "_state" to ref(state, navigatorClass()), "_inheritedElements" to inherited)
            put(state, "NavigatorState", "_element" to ref(element, "StatefulElement"), "_history" to history)
            heap.add(ref(state, navigatorClass()))
            if (nav.keyed) {
                registry.add(
                    JsonObject().apply {
                        add("key", ref(id(), "LabeledGlobalKey<NavigatorState>"))
                        add("value", ref(element, "StatefulElement"))
                    },
                )
            }
        }
        return FixtureObjects(app(registry, heap))
    }

    // ---------------------------------------------------------------- navigation objects

    private fun route(name: String?): JsonObject {
        val settings = id()
        put(settings, "RouteSettings", "name" to (name?.let(::string) ?: nullRef()))
        val route = id()
        put(route, "_PageBasedMaterialPageRoute", "_settings" to ref(settings, "RouteSettings"))
        return ref(route, "_PageBasedMaterialPageRoute")
    }

    private fun entry(route: JsonObject, lifecycle: String): JsonObject {
        val lifecycleId = id()
        put(lifecycleId, "_RouteLifecycle", "_name" to string(lifecycle))
        val entry = id()
        put(entry, "_RouteEntry", "route" to route, "currentState" to ref(lifecycleId, "_RouteLifecycle"))
        return ref(entry, "_RouteEntry")
    }

    private fun list(items: List<JsonElement>): JsonObject {
        val id = id()
        objects.add(
            id,
            instance(id, "_GrowableList", "List").apply {
                addProperty("length", items.size)
                add("elements", JsonArray().apply { items.forEach(::add) })
            },
        )
        return ref(id, "_GrowableList").apply { addProperty("kind", "List") }
    }

    /**
     * The navigator's `_inheritedElements`: one `_CompressedNode` under a `_FullNode`, as the VM
     * showed, with an unrelated scope first so a match has to be looked for.
     */
    private fun inheritedMap(nav: Nav, routeRefs: List<List<JsonObject>>): JsonObject {
        val pairs = mutableListOf<JsonElement>(type("MediaQuery", "package:flutter/src/widgets/media_query.dart"))
        pairs += inheritedElement("MediaQuery")
        var status: String? = null
        nav.inside?.let { (parent, route) ->
            pairs += type("_ModalScopeStatus", "package:flutter/src/widgets/routes.dart")
            val widget = id().also { status = it }
            put(widget, "_ModalScopeStatus", "route" to routeRefs[parent][route], "isCurrent" to bool(true))
            pairs += inheritedElement("_ModalScopeStatus", widget)
        }
        nav.ticker?.let { enabled ->
            pairs += type("_EffectiveTickerMode", "package:flutter/src/widgets/ticker_provider.dart")
            val widget = id()
            put(widget, "_EffectiveTickerMode", "enabled" to bool(enabled))
            pairs += inheritedElement("_EffectiveTickerMode", widget)
        }
        var visibility: String? = null
        nav.visible?.let { visible ->
            pairs += type("_VisibilityScope", visibilityLibrary)
            val widget = id().also { visibility = it }
            put(widget, "_VisibilityScope", "isVisible" to bool(visible))
            pairs += inheritedElement("_VisibilityScope", widget)
        }
        statusWidgetIds += status
        visibilityWidgetIds += visibility
        val compressed = id()
        put(compressed, "_CompressedNode", "keyValuePairs" to list(pairs))
        val full = id()
        put(full, "_FullNode", "descendants" to list(listOf(nullRef(), ref(compressed, "_CompressedNode"))))
        val map = id()
        put(map, "PersistentHashMap", "_root" to ref(full, "_FullNode"))
        return ref(map, "PersistentHashMap")
    }

    private fun inheritedElement(widgetClass: String, widget: String = id().also { put(it, widgetClass) }): JsonObject {
        val element = id()
        put(element, "InheritedElement", "_widget" to ref(widget, widgetClass))
        return ref(element, "InheritedElement")
    }

    /** A `Type` key, as the VM sends one: `kind: Type` and the class it stands for in `typeClass`. */
    private fun type(name: String, library: String) = JsonObject().apply {
        addProperty("type", "@Instance")
        addProperty("kind", "Type")
        addProperty("id", id())
        add("class", JsonObject().apply { addProperty("name", "_Type") })
        add(
            "typeClass",
            JsonObject().apply {
                addProperty("type", "@Class")
                addProperty("id", "classes/$name")
                addProperty("name", name)
                add("library", JsonObject().apply { addProperty("uri", library) })
            },
        )
    }

    // ---------------------------------------------------------------- the app around them

    private fun app(registry: JsonArray, heap: JsonArray): JsonObject {
        binding(registry)
        return JsonObject().apply {
            add(
                "isolate",
                JsonObject().apply {
                    addProperty("type", "Isolate")
                    addProperty("id", "isolates/1")
                    add(
                        "libraries",
                        JsonArray().apply {
                            add(library("libraries/@20", NavigatorReader.NAVIGATOR_LIBRARY))
                            add(library("libraries/@30", NavigatorReader.BINDING_LIBRARY))
                        },
                    )
                },
            )
            add("objects", objects)
            add(
                "instances",
                JsonObject().apply {
                    add(
                        "classes/400",
                        JsonObject().apply {
                            addProperty("type", "InstanceSet")
                            addProperty("totalCount", heap.size())
                            add("instances", heap)
                        },
                    )
                },
            )
        }
    }

    /** `WidgetsBinding._instance` → `_buildOwner` → `_globalKeyRegistry`, holding [registry]. */
    private fun binding(registry: JsonArray) {
        objects.add(
            "libraries/@30",
            library("libraries/@30", NavigatorReader.BINDING_LIBRARY).apply {
                addProperty("type", "Library")
                add("classes", JsonArray().apply { add(classRef("classes/302", "WidgetsBinding")) })
            },
        )
        val instanceField = JsonObject().apply {
            addProperty("type", "@Field")
            addProperty("id", "classes/302/fields/_instance")
            addProperty("name", "_instance")
        }
        objects.add(
            "classes/302",
            classRef("classes/302", "WidgetsBinding").apply {
                addProperty("type", "Class")
                add("fields", JsonArray().apply { add(instanceField) })
            },
        )
        objects.add(
            "classes/302/fields/_instance",
            instanceField.deepCopy().apply {
                addProperty("type", "Field")
                add("staticValue", ref("objects/1", "WidgetsFlutterBinding"))
            },
        )
        put("objects/1", "WidgetsFlutterBinding", "_buildOwner" to ref("objects/2", "BuildOwner"))
        put("objects/2", "BuildOwner", "_globalKeyRegistry" to ref("objects/3", "_Map"))
        objects.add(
            "objects/3",
            instance("objects/3", "_Map", "Map").apply {
                addProperty("length", registry.size())
                add("associations", registry)
            },
        )
    }

    // ---------------------------------------------------------------- JSON

    private fun id() = "objects/${next++}"

    private fun identity(id: String) = 100_000 + id.substringAfter('/').toInt()

    private fun navigatorClass() = classRef("classes/400", "NavigatorState").apply {
        add("library", JsonObject().apply { addProperty("uri", NavigatorReader.NAVIGATOR_LIBRARY) })
    }

    private fun put(id: String, className: String, vararg fields: Pair<String, JsonObject>) {
        objects.add(
            id,
            instance(id, className, "PlainInstance").apply {
                add(
                    "fields",
                    JsonArray().apply {
                        fields.forEach { (name, value) ->
                            add(
                                JsonObject().apply {
                                    addProperty("type", "BoundField")
                                    add("decl", JsonObject().apply { addProperty("name", name) })
                                    add("value", value)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun instance(id: String, className: String, kind: String) = JsonObject().apply {
        addProperty("type", "Instance")
        addProperty("kind", kind)
        addProperty("id", id)
        addProperty("identityHashCode", identity(id))
        add("class", JsonObject().apply { addProperty("name", className) })
    }

    private fun ref(id: String, className: String) = JsonObject().apply {
        addProperty("type", "@Instance")
        addProperty("kind", "PlainInstance")
        addProperty("id", id)
        addProperty("identityHashCode", identity(id))
        add("class", JsonObject().apply { addProperty("name", className) })
    }

    private fun ref(id: String, type: JsonObject) = JsonObject().apply {
        addProperty("type", "@Instance")
        addProperty("kind", "PlainInstance")
        addProperty("id", id)
        addProperty("identityHashCode", identity(id))
        add("class", type)
    }

    private fun classRef(id: String, name: String) = JsonObject().apply {
        addProperty("type", "@Class")
        addProperty("id", id)
        addProperty("name", name)
    }

    private fun library(id: String, uri: String) = JsonObject().apply {
        addProperty("type", "@Library")
        addProperty("id", id)
        addProperty("uri", uri)
    }

    private fun string(value: String) = JsonObject().apply {
        addProperty("type", "@Instance")
        addProperty("kind", "String")
        addProperty("id", id())
        addProperty("valueAsString", value)
    }

    private fun bool(value: Boolean) = JsonObject().apply {
        addProperty("type", "@Instance")
        addProperty("kind", "Bool")
        addProperty("id", "objects/bool-$value")
        addProperty("valueAsString", value.toString())
    }

    private fun nullRef() = FixtureObjects.nullRef()
}
