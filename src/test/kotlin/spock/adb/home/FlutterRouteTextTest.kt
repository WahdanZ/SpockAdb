package spock.adb.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.navigation.Confidence
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.flutter.navigation.FlutterRoute
import spock.adb.flutter.navigation.NavigationConfidence

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
        assertTrue(FlutterRouteText.homeTooltip(keyed).contains("Flutter back stack checks"))
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
}
