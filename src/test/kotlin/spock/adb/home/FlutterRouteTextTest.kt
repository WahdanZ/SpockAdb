package spock.adb.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.ApplicationBackStack
import spock.adb.flutter.navigation.Confidence
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.flutter.navigation.FlutterRoute
import spock.adb.flutter.navigation.NavigationConfidence
import spock.adb.flutter.navigation.NestedNavigator
import spock.adb.models.ActivityData

class FlutterRouteTextTest {
    private val keyed = FlutterNavigationState(
        listOf(FlutterRoute("/", "MaterialPageRoute"), FlutterRoute(null, "DialogRoute")),
        Confidence.KEYED,
    )

    @Test
    fun `home and popup share the current route and mark bottom to top`() {
        val rows = FlutterRouteText.rows(keyed)
        assertEquals("DialogRoute (unnamed)", FlutterRouteText.home(keyed))
        assertEquals(FlutterRouteText.home(keyed), rows.last().text)
        assertEquals("root", rows.first().mark)
        assertEquals("current", rows.last().mark)
        assertTrue(rows.last().unnamed)
        assertTrue(FlutterRouteText.homeTooltip(keyed).contains("App back stack checks"))
    }

    @Test
    fun `App back stack names the app read, and says why there are no activities to list`() {
        val app = "spock.adb.spock_flutter_sample"
        val stack = ApplicationBackStack(app, listOf(ActivityData("$app.MainActivity", emptyList(), "Resumed")))
        val none = ApplicationBackStack(app, emptyList())
        val failed = Result.failure<ApplicationBackStack>(IllegalStateException("device offline"))

        assertEquals("App Back Stack · $app", FlutterRouteText.title(Result.success(stack), "other.app"))
        assertEquals("App Back Stack · other.app", FlutterRouteText.title(failed, "other.app"))
        assertEquals("App Back Stack", FlutterRouteText.title(failed, null))
        assertNull(FlutterRouteText.activitiesNote(Result.success(stack)))
        assertEquals("No activities of $app are running.", FlutterRouteText.activitiesNote(Result.success(none)))
        assertEquals("Could not read the activities: device offline", FlutterRouteText.activitiesNote(failed))
    }

    @Test
    fun `one route is both root and current and unavailable never invents a route`() {
        val one = keyed.copy(stack = keyed.stack.take(1), confidence = Confidence.EXACT, heapWalkMs = 150)
        assertEquals("root · current", FlutterRouteText.rows(one).single().mark)
        assertTrue(FlutterRouteText.footer(one).contains("briefly paused"))
        val missing = FlutterNavigationState.unavailable("Missing _history")
        assertEquals("unavailable", FlutterRouteText.home(missing))
        assertEquals("Missing _history", FlutterRouteText.homeTooltip(missing))
        assertTrue(FlutterRouteText.rows(missing).isEmpty())
    }

    @Test
    fun `nested memory stays with the checked session and isolate until a successful check`() {
        val memory = NestedNavigatorMemory()
        val session = Any()
        memory.record(session, "isolate/1", NavigationConfidence.checked(keyed, 2, 120))
        assertTrue(memory.seen(session, "isolate/1"))
        assertFalse(memory.seen(Any(), "isolate/1"))
        assertFalse(memory.seen(session, "isolate/2"))
        assertEquals(Confidence.UNAVAILABLE, NavigationConfidence.home(keyed, true).confidence)
        memory.record(session, "isolate/1", FlutterNavigationState.unavailable("timeout"))
        assertTrue(memory.seen(session, "isolate/1"))
        memory.record(session, "isolate/1", NavigationConfidence.checked(keyed, 1, 120))
        assertFalse(memory.seen(session, "isolate/1"))
    }

    private fun route(name: String?) = FlutterRoute(name, "_PageBasedMaterialPageRoute")

    /** go_router's tabs with Profile showing, as the reader builds them. */
    private val tabs = FlutterNavigationState(
        listOf(route(null)),
        Confidence.EXACT,
        nested = listOf(
            NestedNavigator(listOf(route("feed"), route("feed-post")), FlutterNavigationState.ROOT, 0, hidden = true),
            NestedNavigator(listOf(route("profile"), route("profile-settings")), FlutterNavigationState.ROOT, 0, false),
        ),
    )

    @Test
    fun `nested navigators - one level in under their route, hidden and showing marked, current at the leaf`() {
        val rows = FlutterRouteText.rows(tabs)

        assertEquals(
            listOf(
                "_PageBasedMaterialPageRoute (unnamed)",
                "Nested navigator",
                "feed",
                "feed-post",
                "Nested navigator",
                "profile",
                "profile-settings",
            ),
            rows.map { it.text },
        )
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 1), rows.map { it.depth })
        assertEquals(listOf("root", "hidden", null, null, "showing", null, "current"), rows.map { it.mark })
        assertEquals(listOf(1, 4), rows.indices.filter { rows[it].header })
        assertEquals("profile-settings", FlutterRouteText.home(tabs))
        assertTrue(FlutterRouteText.homeTooltip(tabs).contains("1 nested navigator to the one showing"))
        assertTrue(FlutterRouteText.footer(tabs).startsWith("Exact: these 3 navigators"))
    }

    @Test
    fun `two navigators not hidden in one route - current is that route, and the footer says why`() {
        val split = tabs.copy(
            confidence = Confidence.KEYED,
            nested = tabs.nested.map { it.copy(hidden = false) },
        )

        assertEquals("_PageBasedMaterialPageRoute (unnamed)", FlutterRouteText.home(split))
        assertEquals("root · current", FlutterRouteText.rows(split).first().mark)
        assertTrue(FlutterRouteText.footer(split).contains("2 navigators inside"))
        assertTrue(FlutterRouteText.homeTooltip(split).contains("can't tell which one is showing"))
        assertTrue(FlutterRouteText.rows(split).none { it.mark == "showing" })
    }
}
