package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Inspector's "copy a selector for this element" — built from the node, checked against its screen. */
class SelectorSuggestionTest {

    private fun dump(name: String): UiTree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText(),
    )

    private val compose = dump("compose-material3.xml")
    private val views = dump("views-navigation-fragment.xml")

    private fun UiTree.node(predicate: (UiNode) -> Boolean): UiNode = nodes().first(predicate)

    @Test
    fun `a test tag is preferred, matched whole and case-sensitively`() {
        val button = compose.node { it.testTag == "checkout_continue" }

        val suggestion = SelectorSuggestion.forNode(button, compose.framework)!!

        assertEquals(SelectorSuggestion.Basis.TEST_TAG, suggestion.basis)
        assertEquals("{\"testTag\":\"checkout_continue\",\"exactTag\":true}", suggestion.mcpJson)
        assertEquals("composeTestRule.onNodeWithTag(\"checkout_continue\")", suggestion.composeTest)
        assertEquals("By.res(\"com.example.compose:id/checkout_continue\")", suggestion.uiAutomator)
        assertEquals(UiSelector(testTag = "checkout_continue", exactTag = true), suggestion.selector)
    }

    @Test
    fun `without a tag, the whole text`() {
        val label = compose.node { it.text == "Continue" }

        val suggestion = SelectorSuggestion.forNode(label, compose.framework)!!

        assertEquals(SelectorSuggestion.Basis.TEXT, suggestion.basis)
        assertEquals("{\"text\":\"Continue\",\"exact\":true}", suggestion.mcpJson)
        assertEquals("composeTestRule.onNodeWithText(\"Continue\")", suggestion.composeTest)
        assertEquals("By.text(\"Continue\")", suggestion.uiAutomator)
    }

    @Test
    fun `without a tag or text, the whole content description`() {
        val icon = node(contentDescription = "Close")

        val suggestion = SelectorSuggestion.forNode(icon, UiFramework.COMPOSE)!!

        assertEquals(SelectorSuggestion.Basis.CONTENT_DESCRIPTION, suggestion.basis)
        assertEquals("{\"contentDescription\":\"Close\",\"exact\":true}", suggestion.mcpJson)
        assertEquals("composeTestRule.onNodeWithContentDescription(\"Close\")", suggestion.composeTest)
        assertEquals("By.desc(\"Close\")", suggestion.uiAutomator)
    }

    @Test
    fun `text wins over a content description, as the plan orders them`() {
        val both = node(text = "Save", contentDescription = "Save the draft")

        assertEquals(SelectorSuggestion.Basis.TEXT, SelectorSuggestion.forNode(both, UiFramework.COMPOSE)?.basis)
    }

    @Test
    fun `a node with nothing to find it by has no selector`() {
        // Index 2 in the fixture: clickable, but no tag, text or description.
        val bare = compose.node { it.clickable && it.label.isBlank() }

        assertNull(SelectorSuggestion.forNode(bare, compose.framework))
    }

    @Test
    fun `a Views screen gets no Compose test finder, and its id is a resource id`() {
        val fab = views.node { it.testTag == "fab" }

        val suggestion = SelectorSuggestion.forNode(fab, views.framework)!!

        assertEquals(UiFramework.VIEWS, views.framework)
        assertNull(suggestion.composeTest)
        assertEquals("By.res(\"com.example.myapplication:id/fab\")", suggestion.uiAutomator)
    }

    @Test
    fun `quotes, backslashes and newlines are escaped for each language`() {
        val tricky = node(text = "Say \"hi\"\\now\nplease")

        val suggestion = SelectorSuggestion.forNode(tricky, UiFramework.COMPOSE)!!

        assertEquals("{\"text\":\"Say \\\"hi\\\"\\\\now\\nplease\",\"exact\":true}", suggestion.mcpJson)
        assertEquals("By.text(\"Say \\\"hi\\\"\\\\now\\nplease\")", suggestion.uiAutomator)
        assertEquals("composeTestRule.onNodeWithText(\"Say \\\"hi\\\"\\\\now\\nplease\")", suggestion.composeTest)
    }

    @Test
    fun `a dollar sign is escaped in Kotlin only, where it would start a template`() {
        val price = node(text = "\$5 off")

        val suggestion = SelectorSuggestion.forNode(price, UiFramework.COMPOSE)!!

        assertEquals("composeTestRule.onNodeWithText(\"\\\$5 off\")", suggestion.composeTest)
        assertEquals("By.text(\"\$5 off\")", suggestion.uiAutomator)
        assertEquals("{\"text\":\"\$5 off\",\"exact\":true}", suggestion.mcpJson)
    }

    @Test
    fun `other control characters become unicode escapes`() {
        assertEquals("\"a\\u0001b\"", SelectorSuggestion.json("a\u0001b"))
        assertEquals("\"tab\\there\"", SelectorSuggestion.java("tab\there"))
    }

    @Test
    fun `a selector that finds only this node is unique`() {
        val button = compose.node { it.testTag == "checkout_continue" }

        val check = SelectorSuggestion.forNode(button, compose.framework)!!.check(compose, button)

        assertTrue(check.isUnique, check.toString())
        assertEquals(1, check.matches)
        assertNull(check.ambiguity)
        assertEquals("Unique on this screen", check.describe())
    }

    @Test
    fun `a whole-text selector is unique where a substring would not be`() {
        // "Home" is also inside "This is home Fragment": the substring default would find both.
        val title = views.node { it.text == "Home" }
        assertEquals(2, UiTreeSearch.findAll(views, UiSelector(text = "Home")).size)

        val check = SelectorSuggestion.forNode(title, views.framework)!!.check(views, title)

        assertTrue(check.isUnique, check.toString())
    }

    @Test
    fun `an ambiguous selector says how many it finds and carries the actions' refusal`() {
        val tree = UiTreeParser.parse(
            """
            <hierarchy><node class="android.widget.FrameLayout" package="p" bounds="[0,0][500,500]">
              <node class="android.widget.Button" package="p" text="Save" clickable="true" bounds="[0,0][100,100]" />
              <node class="android.widget.Button" package="p" text="Save" clickable="true" bounds="[0,200][100,300]" />
            </node></hierarchy>
            """.trimIndent(),
        )
        val first = tree.node { it.text == "Save" }

        val check = SelectorSuggestion.forNode(first, tree.framework)!!.check(tree, first)

        assertFalse(check.isUnique)
        assertEquals(2, check.matches)
        assertTrue(check.matchesNode)
        assertEquals("Matches 2 nodes on this screen — an element action would refuse it", check.describe())
        val ambiguity = checkNotNull(check.ambiguity)
        assertTrue(ambiguity.startsWith("Ambiguous selector"), ambiguity)
        assertTrue(ambiguity.contains("containerTag"), ambiguity)
    }

    @Test
    fun `a node with no area is not found by its own selector, and says so`() {
        val tree = UiTreeParser.parse(
            """
            <hierarchy><node class="android.widget.FrameLayout" package="p" bounds="[0,0][500,500]">
              <node class="android.view.View" package="p" text="Hidden" bounds="[0,0][0,0]" />
            </node></hierarchy>
            """.trimIndent(),
        )
        val hidden = tree.node { it.text == "Hidden" }

        val check = SelectorSuggestion.forNode(hidden, tree.framework)!!.check(tree, hidden)

        assertFalse(check.isUnique)
        assertFalse(check.matchesNode)
        assertEquals(0, check.matches)
        assertTrue(check.describe().contains("no visible area"), check.describe())
    }

    private fun node(text: String = "", contentDescription: String = "", resourceId: String = "") = UiNode(
        className = "android.view.View",
        packageName = "p",
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = UiNode.Bounds(0, 0, 100, 100),
        clickable = true,
        longClickable = false,
        enabled = true,
        focused = false,
        focusable = true,
        scrollable = false,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = emptyList(),
    )
}
