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
    fun `a screen with both Compose and View widgets is reported as hybrid`() {
        val hybrid = dump("compose-material3.xml").replace(
            """<node index="0" text="Checkout" resource-id="" class="android.view.View"""",
            """<node index="0" text="Checkout" resource-id="" class="android.widget.TextView"""",
        )

        assertEquals(UiFramework.HYBRID, UiTreeParser.parse(hybrid).framework)
    }
}
