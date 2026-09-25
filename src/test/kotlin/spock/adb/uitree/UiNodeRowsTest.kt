package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.uitree.RowSegment.Kind

/** How a node reads in the Inspector: its tree row, and the "Copy Tree" text. */
class UiNodeRowsTest {

    private val tree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
    )

    @Test
    fun `the copied tree keeps its format`() {
        // Pasted into bugs and chats; the tree now draws styled segments, but the text is unchanged.
        val expected = """
            FrameLayout
              FrameLayout
                AndroidComposeView
                  View  "Checkout"
                  View  #checkout_continue  · clickable
                    View  "Continue"
                  View  · clickable
                  View  #disabled_action  "Disabled action"  · clickable  DISABLED

        """.trimIndent()

        assertEquals(expected, renderTree(tree.root!!))
    }

    @Test
    fun `a row is the short class, the tag, the text, the description, then flags`() {
        val node = node(
            text = "Continue",
            contentDescription = "Go on",
            resourceId = "p:id/next",
            clickable = true,
            scrollable = true,
            enabled = false,
        )

        assertEquals(
            listOf(
                RowSegment("Button", Kind.CLASS),
                RowSegment("#next", Kind.TEST_TAG),
                RowSegment("\"Continue\"", Kind.TEXT),
                RowSegment("desc=\"Go on\"", Kind.DESCRIPTION),
                RowSegment("clickable", Kind.FLAG),
                RowSegment("scrollable", Kind.FLAG),
                RowSegment("disabled", Kind.FLAG),
            ),
            node.rowSegments(),
        )
    }

    @Test
    fun `a plain container is just its class`() {
        assertEquals(listOf(RowSegment("Button", Kind.CLASS)), node().rowSegments())
    }

    @Test
    fun `long or multi-line text is cut to one short line`() {
        val paragraph = node(text = "First line\n" + "x".repeat(200)).rowSegments()
            .single { it.kind == Kind.TEXT }.text

        assertTrue(paragraph.startsWith("\"First line ⏎ xxx"), paragraph)
        assertTrue(paragraph.endsWith("…\""), paragraph)
        assertTrue(paragraph.length < 70, paragraph)
    }

    @Test
    fun `a row says where a node is only when it is not plainly in the viewport`() {
        val node = node(text = "Row 9")
        fun last(visibility: NodeVisibility?) = node.rowSegments(visibility).last()

        val outside = NodeVisibility(Presence.OUTSIDE_VIEWPORT, null, 0.0)
        assertEquals(RowSegment("outside viewport", Kind.VIEWPORT), last(outside))
        val partial = NodeVisibility(Presence.PARTIALLY_IN_VIEWPORT, UiNode.Bounds(0, 0, 100, 62), 0.62)
        assertEquals(RowSegment("62% in viewport", Kind.VIEWPORT), last(partial))
        val inView = NodeVisibility(Presence.IN_VIEWPORT, node.bounds, 1.0)
        assertEquals(node.rowSegments(), node.rowSegments(inView), "a node in view looks as it always did")
    }

    @Test
    fun `search matches tag, text or description, visible nodes only`() {
        assertEquals(listOf("checkout_continue"), inspectorMatches(tree, "CHECKOUT_", false).map { it.testTag })
        val untagged = inspectorMatches(tree, "contin", false).filter { it.testTag == null }
        assertEquals(listOf("Continue"), untagged.map { it.text })
        assertEquals(3, inspectorMatches(tree, "", interactiveOnly = true).size)
    }

    @Suppress("LongParameterList")
    private fun node(
        text: String = "",
        contentDescription: String = "",
        resourceId: String = "",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
    ) = UiNode(
        className = "android.widget.Button",
        packageName = "p",
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = UiNode.Bounds(0, 0, 100, 100),
        clickable = clickable,
        longClickable = false,
        enabled = enabled,
        focused = false,
        focusable = false,
        scrollable = scrollable,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = emptyList(),
    )
}
