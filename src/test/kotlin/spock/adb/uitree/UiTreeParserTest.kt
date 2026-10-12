package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.flutter.FlutterBuild
import spock.adb.flutter.FlutterBuildCache
import java.util.UUID

class UiTreeParserTest {

    private fun dump(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/uidumps/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    /** Captured from a real emulator running a Navigation-Fragment app. */
    private val viewsTree by lazy { UiTreeParser.parse(dump("views-navigation-fragment.xml")) }
    private val composeTree by lazy { UiTreeParser.parse(dump("compose-material3.xml")) }

    @Test
    fun `View resource IDs do not imply exposed Compose tags in a hybrid screen`() {
        val tree = UiTreeParser.parse(
            """
            <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][500,500]">
              <node class="android.widget.Button" resource-id="p:id/save" bounds="[0,0][100,100]" />
              <node class="androidx.compose.ui.platform.AndroidComposeView" bounds="[0,100][500,500]">
                <node class="android.widget.TextView" text="Compose" bounds="[0,100][100,200]" />
              </node>
            </node></hierarchy>
            """.trimIndent(),
        )
        assertEquals(UiFramework.HYBRID, tree.framework)
        assertEquals(UiTree.TestTagSupport.UNAVAILABLE, tree.testTagSupport)
    }

    @Test
    fun `reads the display rotation off the hierarchy element`() {
        fun rotationOf(hierarchy: String) = UiTreeParser.parse(
            "$hierarchy<node class=\"android.widget.FrameLayout\" bounds=\"[0,0][500,500]\" /></hierarchy>",
        ).rotation

        assertEquals(1, rotationOf("""<hierarchy rotation="1">"""))
        assertEquals(3, rotationOf("""<hierarchy rotation="3">"""))
        assertNull(rotationOf("<hierarchy>"), "an absent rotation is unknown, not 0")
        assertNull(rotationOf("""<hierarchy rotation="sideways">"""))
        assertNull(rotationOf("""<hierarchy rotation="4">"""))
    }

    @Test
    fun `real device dumps report the rotation they were taken at`() {
        listOf("views-navigation-fragment.xml", "compose-material3.xml", "compose-only-real.xml").forEach {
            assertEquals(0, UiTreeParser.parse(dump(it)).rotation, it)
        }
    }

    @Test
    fun `identifies a traditional View hierarchy`() {
        assertEquals(UiFramework.VIEWS, viewsTree.framework)
        assertEquals(UiTree.TestTagSupport.NOT_APPLICABLE, viewsTree.testTagSupport)
    }

    @Test
    fun `reads text, ids and bounds from a real device dump`() {
        val texts = viewsTree.nodes().map { it.text }.filter { it.isNotBlank() }.toList()
        assertTrue("This is home Fragment" in texts, texts.toString())

        val fab = viewsTree.nodes().first { it.resourceId.endsWith("/fab") }
        assertTrue(fab.clickable)
        assertTrue(fab.bounds.hasArea)
    }

    @Test
    fun `identifies a Compose hierarchy by its host view`() {
        // Compose publishes semantics into the accessibility tree; only the AndroidComposeView
        // host identifies it, since its children are reported as plain android.view.View.
        assertEquals(UiFramework.COMPOSE, composeTree.framework)
    }

    @Test
    fun `detects that test tags are exposed as resource ids`() {
        assertEquals(UiTree.TestTagSupport.AVAILABLE, composeTree.testTagSupport)
        assertEquals(
            "checkout_continue",
            composeTree.nodes().first { it.clickable && it.testTag != null }.testTag,
        )
    }

    @Test
    fun `reports test tags as unavailable when the app has not opted in`() {
        // Without testTagsAsResourceId the Compose test tag is simply not in the dump, and
        // saying so is what tells an agent to match on text instead.
        val withoutTags = dump("compose-material3.xml").replace(Regex("""resource-id="[^"]*""""), """resource-id=""""")

        val tree = UiTreeParser.parse(withoutTags)
        assertEquals(UiFramework.COMPOSE, tree.framework)
        assertEquals(UiTree.TestTagSupport.UNAVAILABLE, tree.testTagSupport)
    }

    @Test
    fun `does not treat android platform ids as test tags`() {
        val content = composeTree.nodes().first { it.resourceId == "android:id/content" }
        assertNull(content.testTag)
    }

    @Test
    fun `parses bounds and derives a tappable centre`() {
        val bounds = UiTreeParser.parseBounds("[42,900][1038,1032]")

        assertEquals(42, bounds.left)
        assertEquals(1032, bounds.bottom)
        assertEquals(540, bounds.centerX)
        assertEquals(966, bounds.centerY)
        assertTrue(bounds.hasArea)
    }

    @Test
    fun `malformed bounds degrade instead of throwing`() {
        val bounds = UiTreeParser.parseBounds("nonsense")
        assertEquals(0, bounds.width)
        assertTrue(!bounds.hasArea)
    }

    @Test
    fun `malformed xml yields an empty tree rather than an exception`() {
        val tree = UiTreeParser.parse("<hierarchy><node")
        assertNull(tree.root)
        assertEquals(UiFramework.UNKNOWN, tree.framework)
    }

    @Test
    fun `a real Compose-only screen is not misreported as hybrid`() {
        // Captured from a device running a Compose-only app. Compose reports a Text node's
        // class as android.widget.TextView so screen readers treat it correctly, which made
        // a flat class-name scan call this screen hybrid.
        val tree = UiTreeParser.parse(dump("compose-only-real.xml"))

        assertEquals(UiFramework.COMPOSE, tree.framework)
        assertTrue(
            tree.nodes().any { it.className == "android.widget.TextView" },
            "fixture should contain the TextView that caused the misclassification",
        )
    }

    @Test
    fun `only View widgets outside the Compose subtree make a screen hybrid`() {
        val hybrid = """
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="p"
                    content-desc="" checkable="false" checked="false" clickable="false" enabled="true"
                    focusable="false" focused="false" scrollable="false" long-clickable="false"
                    password="false" selected="false" bounds="[0,0][1080,2220]">
                <node index="0" text="Toolbar" resource-id="" class="android.widget.TextView" package="p"
                      content-desc="" checkable="false" checked="false" clickable="false" enabled="true"
                      focusable="false" focused="false" scrollable="false" long-clickable="false"
                      password="false" selected="false" bounds="[0,0][1080,132]" />
                <node index="1" text="" resource-id="" class="androidx.compose.ui.platform.ComposeView"
                      package="p" content-desc="" checkable="false" checked="false" clickable="false"
                      enabled="true" focusable="false" focused="false" scrollable="false"
                      long-clickable="false" password="false" selected="false" bounds="[0,132][1080,2220]">
                  <node index="0" text="Hello" resource-id="" class="android.widget.TextView" package="p"
                        content-desc="" checkable="false" checked="false" clickable="false" enabled="true"
                        focusable="false" focused="false" scrollable="false" long-clickable="false"
                        password="false" selected="false" bounds="[42,174][400,232]" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        // The toolbar TextView sits outside the ComposeView; the inner one does not.
        assertEquals(UiFramework.HYBRID, UiTreeParser.parse(hybrid).framework)
    }

    private fun node(cls: String, bounds: String, extra: String = "", children: String = "") =
        """<node index="0" text="" resource-id="" class="$cls" package="spock.adb.spock_flutter_sample" """ +
            """content-desc="" checkable="false" checked="false" clickable="false" enabled="true" """ +
            """focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" """ +
            """selected="false" bounds="$bounds" $extra>$children</node>"""

    @Test
    fun `a screen that is one blank surface is opaque, a Flutter screen with semantics is not`() {
        val surface = node("android.view.View", "[0,72][1080,2564]")
        val content = node("android.widget.FrameLayout", "[0,0][1080,2636]", children = surface)
        val blank = UiTreeParser.parse("<?xml version='1.0' ?><hierarchy rotation=\"0\">$content</hierarchy>")
        assertTrue(blank.isOpaqueSurface)

        val login = UiTreeParser.parse(
            checkNotNull(javaClass.getResourceAsStream("/uidumps/flutter-login.xml")).bufferedReader().readText(),
        )
        assertFalse(login.isOpaqueSurface)
    }

    @Test
    fun `a Flutter dump with Semantics identifiers is read as Flutter`() {
        for (name in listOf("flutter-login.xml", "flutter-targeting.xml")) {
            val tree = UiTreeParser.parse(dump(name))
            assertEquals(UiFramework.FLUTTER, tree.framework, name)
            assertEquals(UiTree.TestTagSupport.NOT_APPLICABLE, tree.testTagSupport, name)
        }
    }

    @Test
    fun `a Flutter dump without an identifier cannot be told from Views by the dump alone`() {
        val anonymous = dump("flutter-targeting.xml")
            .replace("resource-id=\"targeting_save\"", "resource-id=\"\"")
            .replace("resource-id=\"result\"", "resource-id=\"\"")

        assertEquals(UiFramework.VIEWS, UiTreeParser.parse(anonymous).framework)
    }

    @Test
    fun `bare ids beside visible text are not Flutter's`() {
        // React Native's testID is a bare resource id too, but its words are text.
        val reactNative = UiTreeParser.parse(
            """
            <hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][500,500]">
              <node class="android.widget.TextView" resource-id="login" text="Log in" bounds="[0,0][100,100]" />
              <node class="android.view.ViewGroup" resource-id="row" bounds="[0,100][100,200]" />
            </node></hierarchy>
            """.trimIndent(),
        )

        assertEquals(UiFramework.VIEWS, reactNative.framework)
    }

    @Test
    fun `the app on the device decides, over what the dump looked like`() {
        val targeting = UiTreeParser.parse(dump("flutter-targeting.xml"))
        val anonymous = targeting.copy(framework = UiFramework.VIEWS)
        val asked = mutableListOf<String>()

        assertEquals(
            UiFramework.FLUTTER,
            FlutterScreen.classify(anonymous) {
                asked += it
                true
            }.framework,
        )
        assertEquals(listOf("spock.adb.spock_flutter_sample"), asked)
        assertEquals(UiFramework.VIEWS, FlutterScreen.classify(targeting) { false }.framework)
        assertEquals(UiFramework.FLUTTER, FlutterScreen.classify(targeting) { null }.framework, "adb failed")
        assertEquals(UiFramework.VIEWS, FlutterScreen.classify(anonymous) { null }.framework)
    }

    @Test
    fun `a failed Flutter check is not repeated for ten seconds`() {
        val targeting = UiTreeParser.parse(dump("flutter-targeting.xml")).copy(framework = UiFramework.VIEWS)
        val serial = "emulator-${UUID.randomUUID()}"
        var now = 0L
        var answer: FlutterBuildCache.Detection = FlutterBuildCache.Detection.AdbFailed
        var reads = 0
        fun classify() = FlutterScreen.classifyOn(targeting, serial, clock = { now }) {
            reads++
            answer
        }.framework

        assertEquals(UiFramework.VIEWS, classify(), "adb failed: the dump's reading stays")
        now = 9_999L
        assertEquals(UiFramework.VIEWS, classify())
        assertEquals(1, reads, "not asked again within ten seconds")

        now = 10_000L
        answer = FlutterBuildCache.Detection.Listed(FlutterBuild.DEBUG)
        assertEquals(UiFramework.FLUTTER, classify())
        assertEquals(2, reads)
        now = 10_001L
        assertEquals(UiFramework.FLUTTER, classify())
        assertEquals(3, reads, "an answer is the build cache's to remember, not this memo's")
    }

    @Test
    fun `a native screen inside a Flutter app stays Views, and Compose stays Compose`() {
        assertEquals(UiFramework.VIEWS, FlutterScreen.classify(viewsTree) { true }.framework)
        assertEquals(UiFramework.COMPOSE, FlutterScreen.classify(composeTree) { true }.framework)
    }

    private fun hierarchy(root: String) =
        UiTreeParser.parse("<?xml version='1.0' ?><hierarchy rotation=\"0\">$root</hierarchy>")

    @Test
    fun `a root with nothing under it is not a drawn surface`() {
        // A dump taken while a window is being torn down or set up: nothing to say about the app yet.
        assertFalse(hierarchy(node("android.widget.FrameLayout", "[0,0][1080,2636]")).isOpaqueSurface)
    }

    @Test
    fun `a screen with no size is not a drawn surface`() {
        val surface = node("android.view.View", "[0,0][0,0]")
        assertFalse(hierarchy(node("android.widget.FrameLayout", "[0,0][0,0]", children = surface)).isOpaqueSurface)
    }

    @Test
    fun `a surface beside anything an agent can act on is not opaque`() {
        val surface = node("android.view.View", "[0,72][1080,2564]")
        listOf("checkable=\"true\"", "scrollable=\"true\"").forEach { flag ->
            val control = node("android.view.View", "[0,0][10,10]").replace(flag.replace("true", "false"), flag)
            val screen = node("android.widget.FrameLayout", "[0,0][1080,2636]", children = surface + control)
            assertFalse(hierarchy(screen).isOpaqueSurface, flag)
        }
    }

    @Test
    fun `the note leads with capturing again, not with Flutter`() {
        assertTrue(OPAQUE_SURFACE_NOTE.substringBefore("Flutter").contains("capture again"), OPAQUE_SURFACE_NOTE)
    }
}
