package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiTreeParserTest {

    private fun dump(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/uidumps/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    /** Captured from a real emulator running a Navigation-Fragment app. */
    private val viewsTree by lazy { UiTreeParser.parse(dump("views-navigation-fragment.xml")) }
    private val composeTree by lazy { UiTreeParser.parse(dump("compose-material3.xml")) }

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
        assertTrue(fab.bounds.isVisible)
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
        assertTrue(bounds.isVisible)
    }

    @Test
    fun `malformed bounds degrade instead of throwing`() {
        val bounds = UiTreeParser.parseBounds("nonsense")
        assertEquals(0, bounds.width)
        assertTrue(!bounds.isVisible)
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
}
