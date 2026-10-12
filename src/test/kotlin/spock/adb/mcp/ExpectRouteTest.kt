package spock.adb.mcp

import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spock.adb.CancellationSignal
import spock.adb.flutter.navigation.Confidence
import spock.adb.flutter.navigation.FlutterNavigationState
import spock.adb.flutter.navigation.FlutterRoute
import spock.adb.flutter.navigation.NavigationConfidence
import spock.adb.mcp.tools.OpenDeepLinkTool
import spock.adb.mcp.tools.RouteCheck
import spock.adb.mcp.tools.RouteExpectation
import spock.adb.mcp.tools.RouteReader
import spock.adb.mcp.tools.TapElementTool
import spock.adb.mcp.tools.ToolContext
import spock.adb.mcp.tools.UiDumpCache
import spock.adb.recipe.BuiltInRecipes
import spock.adb.recipe.RecipeStep
import java.util.concurrent.TimeUnit

/**
 * #182 F-13: `expectRoute` verifies a Flutter destination by the route the app holds, with the
 * other expectations' VERIFIED / NOT OBSERVED / INCONCLUSIVE, and never reads a route it could not
 * read as the wrong one.
 */
class ExpectRouteTest {

    /** Keyed reads answer [keyed] in turn, repeating the last; the heap-walk check answers [checked]. */
    private class ScriptedRoutes(
        private val keyed: List<FlutterNavigationState>,
        private val checked: FlutterNavigationState = keyed.last(),
    ) : RouteReader {
        val asked = mutableListOf<Pair<String?, Boolean>>()
        private var reads = 0

        override fun read(
            context: ToolContext,
            serial: String,
            packageName: String?,
            check: Boolean,
        ): FlutterNavigationState {
            asked += packageName to check
            return if (check) checked else keyed[minOf(reads++, keyed.lastIndex)]
        }
    }

    private class Phone(reply: (String) -> String = { "" }) {
        val commands = mutableListOf<String>()
        val device = mockk<IDevice>(relaxed = true).also { device ->
            every { device.executeShellCommand(any(), any(), any(), any<TimeUnit>()) } answers {
                val command = firstArg<String>()
                commands += command
                val output = when {
                    command.startsWith("cat ") -> SCREEN
                    command == "wm size" -> "Physical size: 1080x2400"
                    command == "wm density" -> "Physical density: 420"
                    else -> reply(command)
                }.toByteArray()
                secondArg<IShellOutputReceiver>().addOutput(output, 0, output.size)
            }
        }
        val context = FakeToolContext(available = listOf(FakeToolContext.device("emulator-5554").copy(device = device)))
        val inputs: List<String> get() = commands.filter { it.startsWith("input ") }
    }

    @BeforeEach
    fun forgetCaptures() = UiDumpCache.shared.invalidate()

    private fun tap(device: Phone, routes: RouteReader, vararg pairs: Pair<String, Any>) =
        TapElementTool(routes).execute(
            JsonObject().apply {
                addProperty("testTag", "nav_a")
                pairs.forEach { (key, value) ->
                    if (value is Number) addProperty(key, value) else addProperty(key, value.toString())
                }
            },
            device.context,
        )

    @Test
    fun `a route that becomes current after the tap is verified, with one tap`() {
        val device = Phone()
        val routes = ScriptedRoutes(listOf(at("/navigation"), at("/navigation"), at("/nav/a")))

        val result = tap(device, routes, "expectRoute" to "/nav/a", "expectTimeoutMs" to 5_000)

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("VERIFIED: the Flutter route '/nav/a' is current after 2 route read(s)"))
        assertTrue(result.text().contains("Before the tap it was '/navigation'."), result.text())
        assertEquals(listOf("input tap 150 150"), device.inputs)
        assertTrue(routes.asked.all { it.first == "p" }, "the session must be the tapped app's: ${routes.asked}")
    }

    @Test
    fun `a route already current before the tap is inconclusive`() {
        val device = Phone()

        val result = tap(device, ScriptedRoutes(listOf(at("/nav/a"))), "expectRoute" to "/nav/a")

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("INCONCLUSIVE: the Flutter route '/nav/a' was already current"))
        assertEquals(1, device.inputs.size)
    }

    @Test
    fun `with no session the tap is still sent once, and the check is inconclusive with the reason`() {
        val device = Phone()
        val none = FlutterNavigationState.unavailable("Spock has no Flutter session with p on emulator-5554.")

        val result = tap(device, ScriptedRoutes(listOf(none)), "expectRoute" to "/nav/a")

        assertTrue(result.isError, result.text())
        val text = result.text()
        assertTrue(text.contains("INCONCLUSIVE"), text)
        assertTrue(text.contains("Spock has no Flutter session with p on emulator-5554."), text)
        assertTrue(text.contains("expectText"), text)
        assertFalse(text.contains("NOT OBSERVED"), text)
        assertEquals(listOf("input tap 150 150"), device.inputs)
    }

    @Test
    fun `a route not seen is not observed only when the check proves every navigator was read`() {
        val device = Phone()
        val exact = at("/navigation").copy(confidence = Confidence.EXACT)

        val routes = ScriptedRoutes(listOf(at("/navigation")), exact)

        val result = tap(device, routes, "expectRoute" to "/nav/a", "expectTimeoutMs" to 0)

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("NOT OBSERVED"), result.text())
        assertTrue(result.text().contains("it is '/navigation' (exact"), result.text())
    }

    @Test
    fun `a navigator without a key that could hide the route makes it inconclusive, not unobserved`() {
        val device = Phone()
        val nested = FlutterNavigationState.unavailable(NavigationConfidence.NESTED_UNKEYED)

        val result = tap(
            device,
            ScriptedRoutes(listOf(at("/nav/unkeyed")), nested),
            "expectRoute" to "/nested",
            "expectTimeoutMs" to 0,
        )

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("INCONCLUSIVE"), result.text())
        assertTrue(result.text().contains("without a GlobalKey"), result.text())
        assertFalse(result.text().contains("NOT OBSERVED"), result.text())
    }

    @Test
    fun `a route that cannot be read after the action is inconclusive`() {
        val device = Phone()
        val paused = FlutterNavigationState.unavailable("The app is paused in the debugger (PauseBreakpoint).")

        val result = tap(
            device,
            ScriptedRoutes(listOf(at("/navigation"), paused)),
            "expectRoute" to "/nav/a",
            "expectTimeoutMs" to 0,
        )

        assertTrue(result.isError, result.text())
        assertTrue(result.text().contains("INCONCLUSIVE"), result.text())
        assertTrue(result.text().contains("paused in the debugger"), result.text())
    }

    @Test
    fun `expectRoute is checked on its own, refused with an element expectation before anything is sent`() {
        val device = Phone()

        val error = assertThrows(IllegalArgumentException::class.java) {
            tap(device, ScriptedRoutes(listOf(at("/"))), "expectRoute" to "/nav/a", "expectText" to "Current")
        }

        assertTrue(error.message!!.contains("expectRoute is checked on its own"), error.message)
        assertTrue(device.commands.isEmpty(), device.commands.toString())
    }

    @Test
    fun `a name matches exactly, or as its path when the expectation has no query`() {
        val expectation = RouteExpectation("/item/42", timeoutMs = 0, requestedMs = 0)

        assertTrue(expectation.matches("/item/42"))
        assertTrue(expectation.matches("/item/42?ref=spock"))
        assertFalse(expectation.matches("/item/421"))
        assertFalse(expectation.matches("/Item/42"))
        assertFalse(expectation.matches(null), "an unnamed route matches nothing")
        assertFalse(RouteExpectation("/", 0, 0).matches("/navigation"))
        assertFalse(RouteExpectation("/item/42?ref=a", 0, 0).matches("/item/42?ref=b"))
    }

    @Test
    fun `a read that meets a transition before the action is tried again`() {
        val moving = FlutterNavigationState.unavailable("navigation in progress", transient = true)
        val routes = ScriptedRoutes(listOf(moving, at("/navigation")))
        val check = RouteCheck(
            RouteExpectation("/nav/a", 0, 0),
            { routes.read(FakeToolContext(), "emulator-5554", null, it) },
            CancellationSignal { false },
            sleep = {},
        )

        assertEquals("/navigation", check.before().current?.name)
    }

    @Test
    fun `a deep link with expectRoute is verified against the package it named`() {
        val device = Phone { command -> if (command.startsWith("am start")) AM_START_OK else "" }
        val routes = ScriptedRoutes(listOf(at("/navigation"), at("/item/42?ref=spock")))

        val result = OpenDeepLinkTool(routes).execute(
            JsonObject().apply {
                addProperty("uri", "spockflutter://open/item/42?ref=spock")
                addProperty("packageName", "spock.adb.spock_flutter_sample")
                addProperty("expectRoute", "/item/42")
            },
            device.context,
        )

        assertFalse(result.isError, result.text())
        assertTrue(result.text().contains("VERIFIED: the Flutter route '/item/42' is current"), result.text())
        assertTrue(routes.asked.all { it.first == "spock.adb.spock_flutter_sample" }, "${routes.asked}")
    }

    @Test
    fun `the deep link recipe passes expectRoute to the deep link, and both declare it`() {
        val open = BuiltInRecipes.deepLink.steps.filterIsInstance<RecipeStep.Call>()
            .single { it.tool == "android_open_deep_link" }

        assertEquals("{expectRoute}", open.arguments["expectRoute"])
        assertTrue(BuiltInRecipes.deepLink.params.any { it.name == "expectRoute" })
        assertTrue(BuiltInRecipes.deepLink.description.contains("expectText"))
        for (schema in listOf(OpenDeepLinkTool().inputSchema, TapElementTool().inputSchema)) {
            assertTrue(schema.getAsJsonObject("properties").has("expectRoute"), "$schema")
        }
    }

    private fun at(vararg names: String) = FlutterNavigationState(
        stack = (listOf("/") + names).map { FlutterRoute(it, "MaterialPageRoute") },
        confidence = Confidence.KEYED,
    )

    private companion object {
        const val SCREEN = """<hierarchy rotation="0"><node class="android.widget.FrameLayout" package="p"
            bounds="[0,0][1080,2400]"><node class="android.widget.Button" content-desc="Push /nav/a"
            resource-id="nav_a" package="p" clickable="true" enabled="true" bounds="[0,100][300,200]" />
            </node></hierarchy>"""
        const val AM_START_OK = "Starting: Intent { act=android.intent.action.VIEW }\nStatus: ok\n" +
            "LaunchState: WARM\nActivity: spock.adb.spock_flutter_sample/.MainActivity\nTotalTime: 80\nComplete"
    }
}
