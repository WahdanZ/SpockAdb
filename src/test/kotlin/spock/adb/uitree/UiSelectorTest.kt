package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `mutations reject ambiguous selectors and can scope to a container`() {
        val first = nodeWithText("Save").copy(clickable = true)
        val second = first.copy(bounds = UiNode.Bounds(0, 200, 100, 300))
        val container = nodeWithText("").copy(resourceId = "dialog", children = listOf(second))
        val duplicateTree = tree.copy(root = nodeWithText("").copy(children = listOf(first, container)))
        val error = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.findUnique(duplicateTree, UiSelector(text = "Save"))
        }
        assertTrue(error.message!!.contains("2 matches"))
        assertEquals(second, UiTreeSearch.findUnique(duplicateTree, UiSelector(text = "Save", containerTag = "dialog")))
        val missing = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.findUnique(duplicateTree, UiSelector(text = "Save", containerTag = "missing"))
        }
        assertTrue(missing.message!!.contains("matched 0 nodes"), missing.message)
    }

    @Test
    fun `ambiguity lists what tells candidates apart and suggests exact matching`() {
        val title = nodeWithText("Save changes?").copy(className = "android.widget.TextView")
        val button = nodeWithText("Save").copy(
            className = "android.widget.Button",
            clickable = true,
            bounds = UiNode.Bounds(0, 200, 100, 300),
        )
        val dialog = tree.copy(root = nodeWithText("").copy(children = listOf(title, button)))

        val error = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.findUnique(dialog, UiSelector(text = "Save"), UiTreeSearch.Action.TAP)
        }.message!!
        assertTrue(error.contains("'Save changes?' TextView"), error)
        assertTrue(error.contains("'Save' Button"), error)
        assertTrue(error.contains("exact: true"), error)
        assertEquals(button, UiTreeSearch.findUnique(dialog, UiSelector(text = "Save", exact = true)))
    }

    @Test
    fun `identical candidates are reported as inseparable by selector fields`() {
        val first = nodeWithText("Delete").copy(clickable = true)
        val second = first.copy(bounds = UiNode.Bounds(0, 200, 100, 300))
        val rows = tree.copy(root = nodeWithText("").copy(children = listOf(first, second)))

        val error = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.findUnique(rows, UiSelector(text = "Delete", exact = true), UiTreeSearch.Action.TAP)
        }.message!!
        assertTrue(error.contains("no selector field can tell them apart"), error)
        assertFalse(error.contains("exact: true"), error)
    }

    @Test
    fun `matches that resolve to the same tap target are not ambiguous`() {
        // A clickable row and its icon sharing a content description are one tap, not two.
        val icon = nodeWithText("").copy(contentDescription = "Share")
        val row = nodeWithText("").copy(contentDescription = "Share", clickable = true, children = listOf(icon))
        val shareTree = tree.copy(root = nodeWithText("").copy(children = listOf(row)))

        val share = UiSelector(contentDescription = "Share")
        val match = UiTreeSearch.findUnique(shareTree, share, UiTreeSearch.Action.TAP)
        assertEquals(row, UiTreeSearch.actionTarget(shareTree, match!!, UiTreeSearch.Action.TAP))
        // Without an action there is nothing to collapse on, so both nodes still count.
        assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.findUnique(shareTree, share)
        }
    }

    @Test
    fun `exact tags are case sensitive while legacy substring matching remains available`() {
        val node = nodeWithText("").copy(resourceId = "Save_Button")
        assertTrue(UiSelector(testTag = "Save", exactTag = false).matches(node))
        assertFalse(UiSelector(testTag = "Save", exactTag = true).matches(node))
        assertFalse(UiSelector(testTag = "save_button", exactTag = true).matches(node))
        assertTrue(UiSelector(testTag = "Save_Button", exactTag = true).matches(node))
        assertFalse(UiSelector(testTag = "Save_Button", exactTag = true, packageName = "other").matches(node))
    }

    @Test
    fun `tap and long press use different eligible ancestors`() {
        val child = nodeWithText("Save")
        val longPress = child.copy(text = "", longClickable = true, children = listOf(child))
        val button = child.copy(text = "", clickable = true, children = listOf(longPress))
        val actionTree = tree.copy(root = button)
        assertEquals(button, UiTreeSearch.actionTarget(actionTree, child, UiTreeSearch.Action.TAP))
        assertEquals(longPress, UiTreeSearch.actionTarget(actionTree, child, UiTreeSearch.Action.LONG_PRESS))
        val notEditable = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.actionTarget(actionTree, child, UiTreeSearch.Action.TEXT_INPUT)
        }
        assertTrue(notEditable.message!!.contains("no eligible target"), notEditable.message)
        val disabled = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.actionTarget(tree.copy(root = button.copy(enabled = false)), child, UiTreeSearch.Action.TAP)
        }
        assertTrue(disabled.message!!.contains("disabled"), disabled.message)
    }

    @Test
    fun `action cannot climb outside the requested container`() {
        val child = nodeWithText("Save").copy(resourceId = "label_container")
        val button = child.copy(resourceId = "outer_button", clickable = true, children = listOf(child))
        val error = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.actionTarget(
                tree.copy(root = button),
                child,
                UiTreeSearch.Action.TAP,
                UiSelector(text = "Save", containerTag = "label_container"),
            )
        }
        assertTrue(error.message!!.contains("outside the selected container"), error.message)
    }

    @Test
    fun `scrolling refuses competing containers and respects scope`() {
        val first = nodeWithText("").copy(scrollable = true, resourceId = "first")
        val second = first.copy(resourceId = "second")
        val lists = tree.copy(root = nodeWithText("").copy(children = listOf(first, second)))
        assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.scrollTarget(lists, UiSelector(text = "Item"))
        }
        assertEquals(second, UiTreeSearch.scrollTarget(lists, UiSelector(text = "Item", containerTag = "second")))
        assertNull(UiTreeSearch.scrollTarget(lists, UiSelector(text = "Item", packageName = "other")))
    }

    @Test
    fun `nested scrollables resolve to the outermost container`() {
        // A vertical feed whose rows are horizontal carousels: common in Compose, and every
        // carousel is inside the feed, so scoping to the feed alone cannot separate them.
        val carousel = nodeWithText("").copy(scrollable = true, resourceId = "carousel")
        val feed = nodeWithText("").copy(
            scrollable = true,
            resourceId = "feed",
            bounds = UiNode.Bounds(0, 0, 100, 1000),
            children = listOf(carousel, carousel.copy(bounds = UiNode.Bounds(0, 200, 100, 300))),
        )
        val screen = nodeWithText("").copy(resourceId = "screen", children = listOf(feed))
        val nested = tree.copy(root = screen)

        assertEquals(feed, UiTreeSearch.scrollTarget(nested, UiSelector(text = "Item", containerTag = "feed")))
        assertEquals(feed, UiTreeSearch.scrollTarget(nested, UiSelector(text = "Item", containerTag = "screen")))
        assertEquals(feed, UiTreeSearch.scrollTarget(nested, UiSelector(text = "Item")))
    }

    @Test
    fun `sibling feeds each holding carousels are still ambiguous`() {
        val carousel = nodeWithText("").copy(scrollable = true, resourceId = "carousel")
        val feed = nodeWithText("").copy(scrollable = true, resourceId = "feed", children = listOf(carousel))
        val lists = tree.copy(root = nodeWithText("").copy(children = listOf(feed, feed.copy(resourceId = "other"))))

        val error = assertThrows(IllegalArgumentException::class.java) {
            UiTreeSearch.scrollTarget(lists, UiSelector(text = "Item"))
        }
        assertTrue(error.message!!.contains("Several scrollable containers"), error.message)
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
