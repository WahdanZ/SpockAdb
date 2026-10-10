package spock.adb.flutter.navigation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.vmservice.VmServiceTimeoutException

/**
 * The navigator reader on hand-built fixtures in the shapes spike S25 read from Flutter 3.22.2 and
 * 3.47.5: the stack bottom to top, what makes it exact, and every way it says "unavailable"
 * instead of guessing.
 */
class NavigatorReaderTest {

    private var now = 1_000L
    private val clock = { now }

    private fun reader(objects: VmObjects, limits: NavigatorLimits = NavigatorLimits()) =
        NavigatorReader(objects, limits, clock)

    private fun names(state: FlutterNavigationState) = state.stack.map { it.name }

    private fun classRef(id: String, name: String, library: String) = JsonObject().apply {
        addProperty("type", "@Class")
        addProperty("id", id)
        addProperty("name", name)
        add(
            "library",
            JsonObject().apply {
                addProperty("type", "@Library")
                addProperty("uri", library)
            },
        )
    }

    @Test
    fun `missing heap total cannot prove the navigator count`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        objects.fixture.getAsJsonObject("instances").entrySet().single().value.asJsonObject.remove("totalCount")
        assertTrue(reader(objects).readChecked().unavailable!!.contains("totalCount"))
    }

    @Test
    fun `malformed history entries cannot silently shorten the stack`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val history = objects.ref(objects.keyedNavigatorIds().single(), "_history").get("id").asString
        val list = objects.ref(history, "_value").get("id").asString
        objects.objects().getAsJsonObject(list).getAsJsonArray("elements").set(0, com.google.gson.JsonNull.INSTANCE)
        assertEquals(Confidence.UNAVAILABLE, reader(objects).readKeyed().confidence)
    }

    @Test
    fun `missing mounted field cannot silently exclude a nested navigator`() {
        val objects = FixtureObjects.load("unkeyed-nested-3.47.5.json")
        val instances = objects.fixture.getAsJsonObject("instances").entrySet().single().value.asJsonObject
        val nestedId = instances.getAsJsonArray("instances").last().asJsonObject.get("id").asString
        objects.removeField(nestedId, "_element")
        val state = reader(objects).readChecked()
        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.unavailable!!.contains("_element"))
    }

    @Test
    fun `navigator subclass is resolved through the VM superclass and checked as NavigatorState`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val navigator = objects.keyedNavigatorIds().single()
        val base = objects.objects().getAsJsonObject(navigator).getAsJsonObject("class").deepCopy()
        val subtype = base.deepCopy().apply {
            addProperty("id", "classes/CustomNavigator")
            addProperty("name", "CustomNavigator")
        }
        objects.objects().entrySet().forEach { (_, value) ->
            value.asJsonObject.getAsJsonArray("fields")?.forEach { field ->
                val ref = field.asJsonObject.getAsJsonObject("value")
                if (ref?.get("id")?.asString == navigator) ref.add("class", subtype)
            }
        }
        objects.objects().add("classes/CustomNavigator", subtype.deepCopy().apply { add("super", base) })
        assertEquals(Confidence.EXACT, reader(objects).readChecked().confidence)
        assertTrue(objects.calls.contains("getInstances ${base.get("id").asString}"))
    }

    @Test
    fun `an app subclass's same-named private field does not stand in for Flutter's`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val navigator = objects.keyedNavigatorIds().single()
        val flutters = objects.field(navigator, "_history").apply {
            val owner = classRef("classes/400", "NavigatorState", NavigatorReader.NAVIGATOR_LIBRARY)
            getAsJsonObject("decl").add("owner", owner)
        }
        val apps = flutters.deepCopy().apply {
            getAsJsonObject("decl").add("owner", classRef("classes/9000", "NavigatorState", "package:app/main.dart"))
            add("value", FixtureObjects.nullRef())
        }
        val state = objects.objects().getAsJsonObject(navigator)
        val fields = JsonArray().apply {
            add(apps)
            state.getAsJsonArray("fields").forEach(::add)
        }
        state.add("fields", fields)

        assertEquals(listOf("/", "/items", "/item/3"), names(reader(objects).readKeyed()))

        objects.removeField(navigator, "_history")
        state.getAsJsonArray("fields").add(apps)
        assertEquals(NavigatorReader.missing("NavigatorState", "_history"), reader(objects).readKeyed().unavailable)
    }

    @Test
    fun `a mixin's field is declared by its mixin application`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val application = "_WidgetsFlutterBinding&BindingBase&GestureBinding&WidgetsBinding"
        objects.field("objects/1", "_buildOwner").getAsJsonObject("decl")
            .add("owner", classRef("classes/9001", application, NavigatorReader.BINDING_LIBRARY))

        assertEquals(Confidence.KEYED, reader(objects).readKeyed().confidence)
    }

    @Test
    fun `an app class named NavigatorState is not Flutter's`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val navigator = objects.keyedNavigatorIds().single()
        val impostor = classRef("classes/9002", "NavigatorState", "package:app/main.dart")
        objects.objects().entrySet().forEach { (_, value) ->
            value.asJsonObject.getAsJsonArray("fields")?.forEach { field ->
                val ref = field.asJsonObject.getAsJsonObject("value")
                if (ref?.get("id")?.asString == navigator) ref.add("class", impostor)
            }
        }
        objects.objects().add(
            "classes/9002",
            impostor.deepCopy().apply { add("super", classRef("classes/Object", "Object", "dart:core")) },
        )

        assertEquals(NavigatorReader.NO_KEYED_NAVIGATOR, reader(objects).readKeyed().unavailable)
    }

    @Test
    fun `3_22_2 - the root navigator's stack, bottom to top, keyed`() {
        val state = reader(FixtureObjects.load("root-3.22.2.json")).readKeyed()

        assertEquals(Confidence.KEYED, state.confidence)
        assertEquals(listOf("/", "/items", "/item/3"), names(state))
        assertTrue(state.stack.all { it.routeClass == "MaterialPageRoute" })
        assertEquals(FlutterRoute("/item/3", "MaterialPageRoute"), state.current)
        assertNull(state.unavailable)
    }

    @Test
    fun `3_47_5 - the same fields, and the ones it adds are ignored`() {
        val state = reader(FixtureObjects.load("root-3.47.5.json")).readKeyed()

        assertEquals(Confidence.KEYED, state.confidence)
        assertEquals(listOf("/", "/items", "/item/3", "/item/4"), names(state))
    }

    @Test
    fun `the keyed read never walks the heap`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        reader(objects).readKeyed()

        assertTrue(objects.calls.none { it.startsWith("getInstances") })
        // About twenty plain reads, as the spike measured; nothing that is not a StatefulElement is opened.
        assertTrue(objects.calls.size < 40, "${objects.calls.size} calls")
    }

    @Test
    fun `the check is exact when the keyed navigator is the only one mounted`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val reader = NavigatorReader(objects, NavigatorLimits()) { now.also { now += 150 } }

        val state = reader.readChecked()

        assertEquals(Confidence.EXACT, state.confidence)
        assertEquals(listOf("/", "/items", "/item/3"), names(state))
        assertEquals(150L, state.heapWalkMs)
        assertEquals(1, objects.calls.count { it.startsWith("getInstances") })
    }

    @Test
    fun `the check counts only mounted navigators - one whose screen closed is not`() {
        // root-3.22.2's heap walk finds two NavigatorStates; the second has no _element.
        val objects = FixtureObjects.load("root-3.22.2.json")
        assertEquals(
            2,
            objects.fixture.getAsJsonObject("instances").entrySet().single().value.asJsonObject.get("totalCount").asInt
        )

        assertEquals(Confidence.EXACT, reader(objects).readChecked().confidence)
    }

    @Test
    fun `3_47_5 - the check is exact too`() {
        assertEquals(Confidence.EXACT, reader(FixtureObjects.load("root-3.47.5.json")).readChecked().confidence)
    }

    @Test
    fun `a dialog is current, by its class, with no name invented`() {
        val state = reader(FixtureObjects.load("dialog-3.47.5.json")).readKeyed()

        assertEquals(FlutterRoute(null, "DialogRoute"), state.current)
        assertEquals(listOf("/", "/navigation", null), names(state))
    }

    @Test
    fun `Navigator_pages - names from each Page, and a page with none by its route class`() {
        val state = reader(FixtureObjects.load("pages-3.22.2.json")).readKeyed()

        assertEquals(Confidence.KEYED, state.confidence)
        assertEquals(listOf("/pages/list", null), names(state))
        assertEquals("_PageBasedMaterialPageRoute", state.current?.routeClass)
    }

    @Test
    fun `a route in transition makes the whole stack unavailable, and worth reading again`() {
        val objects = FixtureObjects.load("pushing-3.22.2.json")
        val state = reader(objects).readChecked()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.stack.isEmpty())
        assertTrue(state.transient)
        assertEquals(NavigatorReader.inProgress("MaterialPageRoute", "pushing"), state.unavailable)
        assertTrue(objects.calls.none { it.startsWith("getInstances") }, "no heap walk for a stack not read")
    }

    @Test
    fun `every lifecycle but idle is a transition`() {
        val transitions = listOf(
            "staging", "add", "adding", "push", "pushReplace", "pushing", "replace", "pop", "complete", "remove",
            "popping", "removing", "dispose", "disposing", "disposed",
        )
        transitions.forEach { lifecycle ->
            val objects = FixtureObjects.load("root-3.22.2.json")
            val enumId = objects.objects().entrySet().first { it.key.startsWith("objects/lifecycle-") }.key
            val name = objects.ref(enumId, "_name").deepCopy().apply { addProperty("valueAsString", lifecycle) }
            objects.setField(enumId, "_name", name)

            val state = reader(objects).readKeyed()

            assertEquals(Confidence.UNAVAILABLE, state.confidence, lifecycle)
            assertTrue(state.transient, lifecycle)
        }
    }

    @Test
    fun `two keyed navigators - several, and no guess at which is showing`() {
        val objects = FixtureObjects.load("several-keyed-3.22.2.json")

        val keyed = reader(objects).readKeyed()
        val checked = reader(objects).readChecked()

        assertEquals(NavigatorReader.severalKeyed(2), keyed.unavailable)
        assertEquals(Confidence.UNAVAILABLE, keyed.confidence)
        assertEquals(keyed, checked)
        assertTrue(objects.calls.none { it.startsWith("getInstances") })
    }

    @Test
    fun `an unkeyed nested navigator - keyed shows the root, the check says unavailable`() {
        val keyed = reader(FixtureObjects.load("unkeyed-nested-3.47.5.json")).readKeyed()
        val checked = reader(FixtureObjects.load("unkeyed-nested-3.47.5.json")).readChecked()

        assertEquals(Confidence.KEYED, keyed.confidence)
        assertEquals("/nav/unkeyed", keyed.current?.name)
        assertEquals(Confidence.UNAVAILABLE, checked.confidence)
        assertEquals(NavigationConfidence.NESTED_UNKEYED, checked.unavailable)
        assertTrue(checked.stack.isEmpty())
        assertTrue(checked.heapWalkMs != null)
    }

    @Test
    fun `a field this Flutter version does not have is unavailable, by name`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        objects.removeField(objects.keyedNavigatorIds().single(), "_history")

        val state = reader(objects).readKeyed()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertEquals(NavigatorReader.missing("NavigatorState", "_history"), state.unavailable)
        assertFalse(state.transient)
    }

    @Test
    fun `a missing key registry is named too`() {
        val objects = FixtureObjects.load("root-3.47.5.json")
        objects.removeField("objects/2", "_globalKeyRegistry")

        assertEquals(
            NavigatorReader.missing("BuildOwner", "_globalKeyRegistry"),
            reader(objects).readKeyed().unavailable
        )
    }

    @Test
    fun `an isolate with no widgets binding is not a Flutter UI isolate`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        objects.fixture.getAsJsonObject("isolate").getAsJsonArray("libraries")
            .removeAll { it.asJsonObject.get("uri").asString == NavigatorReader.BINDING_LIBRARY }

        val state = reader(objects).readKeyed()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.unavailable!!.contains(NavigatorReader.BINDING_LIBRARY))
    }

    @Test
    fun `a history that is a plain list is read the same`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val navigator = objects.keyedNavigatorIds().single()
        val history = objects.ref(navigator, "_history").get("id").asString
        objects.setField(navigator, "_history", objects.ref(history, "_value"))

        assertEquals(listOf("/", "/items", "/item/3"), names(reader(objects).readKeyed()))
    }

    @Test
    fun `a keyed navigator that is no longer mounted is not read`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        objects.setField(objects.keyedNavigatorIds().single(), "_element", FixtureObjects.nullRef())

        assertEquals(NavigatorReader.NO_KEYED_NAVIGATOR, reader(objects).readKeyed().unavailable)
    }

    @Test
    fun `part of a history proves nothing`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val history = objects.ref(objects.keyedNavigatorIds().single(), "_history").get("id").asString
        val list = objects.ref(history, "_value").get("id").asString
        objects.objects().getAsJsonObject(list).addProperty("length", 4)

        val state = reader(objects).readKeyed()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.unavailable!!.contains("3 of the 4"), state.unavailable)
    }

    @Test
    fun `an object the app let go of mid-read is unavailable, not a shorter stack`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val history = objects.ref(objects.keyedNavigatorIds().single(), "_history").get("id").asString
        objects.objects().remove(history)

        val state = reader(objects).readKeyed()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.stack.isEmpty())
    }

    @Test
    fun `more keyed stateful widgets than the bound - unavailable rather than a long read`() {
        val objects = FixtureObjects.load("root-3.22.2.json")

        val state = reader(objects, NavigatorLimits(maxElements = 2)).readKeyed()

        assertEquals(NavigatorReader.tooManyElements(2), state.unavailable)
        // Counted before any is opened: the registry holds three StatefulElements here.
        assertTrue(objects.calls.none { it.endsWith(objects.keyedNavigatorIds().single()) })
    }

    @Test
    fun `a heap walk that found more than it returned cannot count`() {
        val objects = FixtureObjects.load("root-3.22.2.json")

        val state = reader(objects, NavigatorLimits(maxInstances = 1)).readChecked()

        assertEquals(NavigationConfidence.tooManyInstances(1), state.unavailable)
    }

    @Test
    fun `a name the VM shortened is opened for the rest`() {
        val objects = FixtureObjects.load("root-3.22.2.json")
        val long = "/item/" + "9".repeat(200)
        val settings = objects.objects().entrySet()
            .first { (_, value) ->
                value.asJsonObject.getAsJsonObject("class")?.get("name")?.asString == "RouteSettings" &&
                objects.ref(value.asJsonObject.get("id").asString, "name").get("valueAsString")?.asString == "/item/3"
            }
            .key
        val name = objects.ref(settings, "name")
        val nameId = name.get("id").asString
        objects.objects().add(nameId, name.deepCopy().apply { addProperty("valueAsString", long) })
        objects.setField(
            settings,
            "name",
            name.deepCopy().apply {
                addProperty("valueAsString", long.take(128))
                addProperty("valueAsStringIsTruncated", true)
            },
        )

        assertEquals(long, reader(objects).readKeyed().current?.name)
    }

    @Test
    fun `a call that fails makes the read unavailable, in words`() {
        val failing = object : VmObjects by FixtureObjects.load("root-3.22.2.json") {
            override fun get(objectId: String): JsonObject = throw VmServiceTimeoutException("getObject", 3_000)
        }

        val state = reader(failing).readKeyed()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.unavailable!!.startsWith("Could not read the navigator"), state.unavailable)
    }
}
