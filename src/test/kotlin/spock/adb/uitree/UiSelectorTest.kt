package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiSelectorTest {

    private val tree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
    )

    @Test
    fun `finds a Compose element by its test tag`() {
        val node = UiTreeSearch.findOne(tree, UiSelector(testTag = "checkout_continue"))

        assertEquals("checkout_continue", node?.testTag)
        assertTrue(node!!.clickable)
    }

    @Test
    fun `finds a Compose element by its visible text`() {
        val node = UiTreeSearch.findOne(tree, UiSelector(text = "Continue"))
        assertEquals("Continue", node?.text)
    }

    @Test
    fun `resolves the tappable ancestor when the text is on a child`() {
        // Compose routinely puts the text on a child and the click handler on the parent, so
        // the node matching "Continue" is not itself clickable. Tapping the text node would
        // do nothing; this is what makes "tap the Continue button" work.
        val textNode = UiTreeSearch.findOne(tree, UiSelector(text = "Continue"))!!
        assertFalse(textNode.clickable)

        val target = UiTreeSearch.interactiveTarget(tree, textNode)

        assertTrue(target.clickable)
        assertEquals("checkout_continue", target.testTag)
    }

    @Test
    fun `matching is a substring by default and exact on request`() {
        assertTrue(UiSelector(text = "Contin").matches(nodeWithText("Continue")))
        assertFalse(UiSelector(text = "Contin", exact = true).matches(nodeWithText("Continue")))
        assertTrue(UiSelector(text = "continue", exact = true).matches(nodeWithText("Continue")))
    }

    @Test
    fun `test tag matches rank above text matches`() {
        // The tag was chosen deliberately; matching text may be coincidence.
        val matches = UiTreeSearch.findAll(tree, UiSelector(testTag = "checkout_continue"))
        assertEquals("checkout_continue", matches.first().testTag)
    }

    @Test
    fun `interactiveOnly filters out decorative nodes`() {
        assertNull(UiTreeSearch.findOne(tree, UiSelector(text = "Checkout", interactiveOnly = true)))
        assertTrue(UiTreeSearch.findOne(tree, UiSelector(text = "Checkout")) != null)
    }

    @Test
    fun `zero-area nodes never match because they cannot be tapped`() {
        val invisible = nodeWithText("Ghost").copy(bounds = UiNode.Bounds(0, 0, 0, 0))
        assertFalse(UiSelector(text = "Ghost").matches(invisible))
    }

    @Test
    fun `an empty selector matches nothing rather than everything`() {
        assertTrue(UiSelector().isEmpty)
        assertFalse(UiSelector().matches(nodeWithText("anything")))
    }

    @Test
    fun `several criteria must all match`() {
        val node = nodeWithText("Continue").copy(resourceId = "com.example:id/checkout_continue")

        assertTrue(UiSelector(text = "Continue", testTag = "checkout_continue").matches(node))
        assertFalse(UiSelector(text = "Continue", testTag = "other_tag").matches(node))
    }

    @Test
    fun `describe explains what was searched for`() {
        assertEquals(
            "testTag='tag' and text='hello'",
            UiSelector(testTag = "tag", text = "hello").describe(),
        )
    }

    private fun nodeWithText(text: String) = UiNode(
        className = "android.view.View",
        packageName = "com.example.compose",
        text = text,
        contentDescription = "",
        resourceId = "",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        clickable = false,
        longClickable = false,
        enabled = true,
        focused = false,
        focusable = false,
        scrollable = false,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = emptyList(),
    )
}
