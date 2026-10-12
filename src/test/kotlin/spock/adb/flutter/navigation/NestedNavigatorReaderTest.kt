package spock.adb.flutter.navigation

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.navigation.FlutterNavigationState.Companion.ROOT

/**
 * Several keyed navigators (#180), in the shapes spike S26 read from the sample's go_router and
 * auto_route fixtures on Flutter 3.22.2 and 3.47.5: the tree, which branch is showing, and every
 * way it stays unavailable rather than guess.
 */
class NestedNavigatorReaderTest {

    private var now = 1_000L

    private fun reader(objects: VmObjects, limits: NavigatorLimits = NavigatorLimits()) =
        NavigatorReader(objects, limits, { now })

    private fun names(routes: List<FlutterRoute>) = routes.map { it.name }

    /** go_router's StatefulShellRoute with Profile showing: the shell page, then a branch per tab. */
    private fun goRouterTabs(): Pair<NavigatorFixture, FixtureObjects> {
        val app = NavigatorFixture()
        val root = app.navigator(null)
        app.navigator("feed", "feed-post", inside = root to 0, ticker = false, visible = false)
        app.navigator("profile", "profile-settings", inside = root to 0, ticker = true, visible = true)
        return app to app.build()
    }

    @Test
    fun `go_router tabs - the branch showing is current, the other is hidden`() {
        val state = reader(goRouterTabs().second).readKeyed()

        assertEquals(Confidence.KEYED, state.confidence)
        assertEquals(listOf(null), names(state.stack))
        val branches = listOf(listOf("feed", "feed-post"), listOf("profile", "profile-settings"))
        assertEquals(branches, state.nested.map { names(it.stack) })
        assertEquals(listOf(true, false), state.nested.map { it.hidden })
        assertTrue(state.nested.all { it.parent == ROOT && it.parentRoute == 0 })
        assertEquals("profile-settings", state.current?.name)
        assertEquals(0, state.undecided)
        assertEquals(
            listOf(FlutterNavigationState.Step(ROOT, 0), FlutterNavigationState.Step(1, 1)),
            state.currentPath,
        )
    }

    @Test
    fun `the check proves the tree exact when the heap holds just these navigators`() {
        val (_, objects) = goRouterTabs()
        val state = reader(objects).readChecked()

        assertEquals(Confidence.EXACT, state.confidence)
        assertEquals("profile-settings", state.current?.name)
        assertEquals(1, objects.calls.count { it.startsWith("getInstances") })
    }

    @Test
    fun `auto_route tabs on 3_47 - only IndexedStack's visibility tells the hidden tab`() {
        val app = NavigatorFixture(visibilityLibrary = "package:flutter/src/widgets/indexed_stack.dart")
        val root = app.navigator("HomeTabsRoute")
        app.navigator("FeedListRoute", "FeedPostRoute", inside = root to 0, ticker = true, visible = true)
        app.navigator("ProfileHomeRoute", "ProfileSettingsRoute", inside = root to 0, ticker = true, visible = false)

        val state = reader(app.build()).readKeyed()

        assertEquals(listOf(false, true), state.nested.map { it.hidden })
        assertEquals("FeedPostRoute", state.current?.name)
    }

    @Test
    fun `auto_route nested router pushed over the tabs - current follows the top route`() {
        val app = NavigatorFixture()
        val root = app.navigator("HomeTabsRoute", "NestedRoute")
        // A covered route's subtree has TickerMode off.
        app.navigator("FeedListRoute", inside = root to 0, ticker = false, visible = true)
        app.navigator("ProfileHomeRoute", inside = root to 0, ticker = false, visible = false)
        app.navigator("NestedARoute", "NestedBRoute", inside = root to 1, ticker = true)

        val state = reader(app.build()).readKeyed()

        assertEquals("NestedBRoute", state.current?.name)
        // The third nested navigator: `nested` leaves the root out.
        assertEquals(2, state.currentPath.last().navigator)
        assertEquals(listOf(0, 0, 1), state.nested.map { it.parentRoute })
    }

    @Test
    fun `a navigator inside a nested one is placed two levels down, outermost first`() {
        val app = NavigatorFixture()
        // Listed innermost first, as the key registry may.
        app.navigator("inner-a", "inner-b", inside = 1 to 0)
        val shell = app.navigator("shell", inside = 2 to 0)
        app.navigator("/")

        val state = reader(app.build()).readKeyed()

        assertEquals(listOf(listOf("shell"), listOf("inner-a", "inner-b")), state.nested.map { names(it.stack) })
        assertEquals(listOf(ROOT, 0), state.nested.map { it.parent })
        assertEquals("inner-b", state.current?.name)
        assertTrue(shell >= 0)
    }

    @Test
    fun `two navigators showing in one route - current stops at that route and says so`() {
        val app = NavigatorFixture()
        val root = app.navigator("/split")
        app.navigator("master", inside = root to 0, visible = true)
        app.navigator("detail", inside = root to 0)

        val state = reader(app.build()).readKeyed()

        assertEquals("/split", state.current?.name)
        assertEquals(2, state.undecided)
    }

    @Test
    fun `every branch hidden - the route holding them is current`() {
        val app = NavigatorFixture()
        val root = app.navigator("/tabs")
        app.navigator("a", inside = root to 0, visible = false)
        app.navigator("b", inside = root to 0, ticker = false)

        val state = reader(app.build()).readKeyed()

        assertEquals("/tabs", state.current?.name)
        assertEquals(0, state.undecided)
    }

    @Test
    fun `an app's own _VisibilityScope does not count as Flutter's`() {
        val app = NavigatorFixture(visibilityLibrary = "package:my_app/visibility.dart")
        val root = app.navigator("/tabs")
        app.navigator("a", inside = root to 0, visible = false)
        app.navigator("b", inside = root to 0, visible = true)

        val state = reader(app.build()).readKeyed()

        assertEquals(listOf(false, false), state.nested.map { it.hidden })
        assertEquals(2, state.undecided)
    }

    @Test
    fun `two navigators outside every route - no root, unavailable`() {
        val app = NavigatorFixture()
        app.navigator("/a")
        app.navigator("/b")

        assertEquals(NavigatorReader.rootsNotOne(2), reader(app.build()).readKeyed().unavailable)
    }

    @Test
    fun `a keyed navigator inside an unkeyed one's route cannot be placed`() {
        val app = NavigatorFixture()
        val root = app.navigator("/")
        val unkeyed = app.navigator("/hidden-host", inside = root to 0, keyed = false)
        app.navigator("inner", inside = unkeyed to 0)

        assertEquals(NavigatorReader.OUTSIDE_KEYED, reader(app.build()).readKeyed().unavailable)
    }

    @Test
    fun `an unkeyed navigator in the heap besides the tree - the check says unavailable`() {
        val app = NavigatorFixture()
        val root = app.navigator("/tabs")
        app.navigator("a", inside = root to 0, visible = true)
        app.navigator("loose", inside = root to 0, keyed = false)

        val keyed = reader(app.build()).readKeyed()
        val checked = reader(app.build()).readChecked()

        assertEquals("a", keyed.current?.name)
        assertEquals(NavigationConfidence.NESTED_UNKEYED, checked.unavailable)
    }

    @Test
    fun `a nested navigator mid-transition - unavailable for now, not a stack with an entry dropped`() {
        val app = NavigatorFixture()
        val root = app.navigator("/")
        app.navigator("a", "b", inside = root to 0, lifecycle = "pushing")

        val state = reader(app.build()).readKeyed()

        assertEquals(Confidence.UNAVAILABLE, state.confidence)
        assertTrue(state.transient)
    }

    @Test
    fun `a tab switched during the check - changed, read again`() {
        val (app, objects) = goRouterTabs()
        // After the heap walk the feed branch shows and the profile one hides.
        val switching = object : VmObjects by objects {
            var walked = false

            override fun instances(classId: String, limit: Int): JsonObject {
                walked = true
                listOf(1 to true, 2 to false).forEach { (navigator, visible) ->
                    val widget = app.visibilityWidgetIds[navigator]!!
                    val flag = objects.ref(widget, "isVisible").deepCopy().apply {
                        addProperty("valueAsString", visible.toString())
                    }
                    objects.setField(widget, "isVisible", flag)
                }
                return objects.instances(classId, limit)
            }
        }

        val state = reader(switching).readChecked()

        assertTrue(switching.walked)
        assertEquals(NavigationConfidence.CHANGED, state.unavailable)
        assertTrue(state.transient)
    }

    @Test
    fun `more keyed navigators than the limit - unavailable, by the count`() {
        val app = NavigatorFixture()
        val root = app.navigator("/")
        repeat(3) { app.navigator("tab $it", inside = root to 0) }

        val state = reader(app.build(), NavigatorLimits(maxNavigators = 3)).readKeyed()

        assertEquals(NavigatorReader.tooManyNavigators(4, 3), state.unavailable)
    }

    @Test
    fun `an inherited map bigger than the bound - unavailable, not half-read`() {
        val (_, objects) = goRouterTabs()

        val state = reader(objects, NavigatorLimits(maxInheritedNodes = 1)).readKeyed()

        assertEquals(NavigatorReader.tooManyInherited(1), state.unavailable)
    }

    @Test
    fun `a Flutter that moved _ModalScopeStatus's route - unavailable, naming it`() {
        val (app, objects) = goRouterTabs()
        objects.removeField(app.statusWidgetIds[1]!!, "route")

        val state = reader(objects).readKeyed()

        assertEquals(NavigatorReader.missing("_ModalScopeStatus", "route"), state.unavailable)
    }

    @Test
    fun `only getIsolate, getObject and getInstances are asked`() {
        val (_, objects) = goRouterTabs()
        reader(objects).readChecked()

        val asked = objects.calls.map { it.substringBefore(' ') }.toSet()
        assertEquals(setOf("getIsolate", "getObject", "getInstances"), asked)
    }
}
