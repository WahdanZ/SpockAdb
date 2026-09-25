package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which relatives an element with nothing of its own to find borrows from, in which order, and
 * what the Source line says about them.
 */
class SourceRelativesTest {

    @Test
    fun `a button with one text inside borrows its label first`() {
        val save = node(text = "Save")
        val button = node(clickable = true, children = listOf(save, node(className = "android.widget.Button")))
        val tree = tree(node(resourceId = "form_a", children = listOf(button)))

        val relatives = SourceRelatives.of(tree, button)

        assertSame(save, relatives.first().node)
        assertEquals(SourceRelative.Kind.LABEL, relatives.first().kind)
        assertEquals("via its label 'Save'", relatives.first().description)
        assertEquals("Save", relatives.first().query.text)
        // The empty Button node inside it has nothing to search for, so it is not a relative.
        assertEquals(listOf(SourceRelative.Kind.LABEL, SourceRelative.Kind.ANCESTOR), relatives.map { it.kind })
    }

    @Test
    fun `an element with text of its own has no label to borrow`() {
        val inner = node(text = "Inner")
        val outer = node(text = "Outer", children = listOf(inner))

        val relatives = SourceRelatives.of(tree(outer), outer)

        assertEquals(listOf(SourceRelative.Kind.DESCENDANT), relatives.map { it.kind })
        assertEquals("via 'Inner' inside it", relatives.single().description)
    }

    @Test
    fun `with several texts inside, what is inside is tried breadth first`() {
        val deep = node(text = "Deep")
        val title = node(text = "Title")
        val list = node(resourceId = "item_list", children = listOf(deep))
        val card = node(children = listOf(node(children = listOf(title)), list))

        val relatives = SourceRelatives.of(tree(card), card)

        assertEquals(listOf(list, title, deep), relatives.map { it.node })
        assertTrue(relatives.all { it.kind == SourceRelative.Kind.DESCENDANT })
    }

    @Test
    fun `what is inside is looked for three levels down, and at most four of it`() {
        val deepest = node(text = "Three down")
        val tooDeep = node(text = "Four down")
        val chain = node(children = listOf(node(children = listOf(node(children = listOf(deepest))))))
        val longer = (1..3).fold(node(children = listOf(tooDeep))) { child, _ -> node(children = listOf(child)) }
        val wide = node(children = (1..6).map { node(text = "Row $it") })

        assertEquals(listOf(deepest), SourceRelatives.of(tree(chain), chain).map { it.node })
        assertTrue(SourceRelatives.of(tree(longer), longer).isEmpty())
        assertEquals(
            listOf("Row 1", "Row 2", "Row 3", "Row 4"),
            SourceRelatives.of(tree(wide), wide).map { it.node.text },
        )
        assertEquals(SourceRelatives.MAX_DESCENDANTS, SourceRelatives.of(tree(wide), wide).size)
    }

    @Test
    fun `then what encloses it, nearest first, skipping containers with nothing to search for`() {
        val row = node()
        val section = node(resourceId = "feed_section", children = listOf(node(children = listOf(row))))
        val screen = node(resourceId = "screen", children = listOf(section))
        val root = node(children = listOf(screen))

        val relatives = SourceRelatives.of(tree(root), row)

        assertEquals(listOf(section, screen), relatives.map { it.node })
        assertEquals("via enclosing 'feed_section'", relatives.first().description)
    }

    @Test
    fun `at most three enclosing elements are tried`() {
        val leaf = node()
        val root = (1..5).fold(leaf) { child, level -> node(resourceId = "level_$level", children = listOf(child)) }

        val relatives = SourceRelatives.of(tree(root), leaf)

        assertEquals(listOf("level_1", "level_2", "level_3"), relatives.map { it.node.resourceId })
    }

    @Test
    fun `identical rows are told apart by identity, so each finds its own section`() {
        val first = node(clickable = true)
        val second = node(clickable = true)
        val root = node(
            children = listOf(
                node(resourceId = "section_a", children = listOf(first)),
                node(resourceId = "section_b", children = listOf(second)),
            ),
        )

        assertEquals(first, second)
        assertEquals("section_b", SourceRelatives.of(tree(root), second).single().node.resourceId)
        assertEquals("section_a", SourceRelatives.of(tree(root), first).single().node.resourceId)
    }

    @Test
    fun `an element not in the captured tree, or with no tree, has only what is inside it`() {
        val label = node(text = "Label")
        val stray = node(children = listOf(label))

        assertEquals(listOf(label), SourceRelatives.of(tree(node(resourceId = "other")), stray).map { it.node })
        assertEquals(listOf(label), SourceRelatives.of(null, stray).map { it.node })
    }

    @Test
    fun `a relative is named by its label, else its class`() {
        val badge = node(className = "com.app.BadgeView")
        val holder = node(children = listOf(badge))
        val long = node(text = "x".repeat(60))

        assertEquals("via 'BadgeView' inside it", SourceRelatives.of(tree(holder), holder).single().description)
        val description = SourceRelatives.of(tree(node(children = listOf(long))), node(children = listOf(long)))
            .single().description
        assertTrue(description.contains("…"), description)
    }

    private fun tree(root: UiNode) = UiTree(root, UiFramework.COMPOSE, UiTree.TestTagSupport.AVAILABLE)

    private fun node(
        text: String = "",
        resourceId: String = "",
        className: String = "android.view.View",
        clickable: Boolean = false,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        className = className,
        packageName = "p",
        text = text,
        contentDescription = "",
        resourceId = resourceId,
        bounds = UiNode.Bounds(0, 0, 100, 100),
        clickable = clickable,
        longClickable = false,
        enabled = true,
        focused = false,
        focusable = false,
        scrollable = false,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = children,
    )
}
