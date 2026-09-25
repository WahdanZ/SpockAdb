package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NodePropertiesTest {

    @Test
    fun `px convert to dp at the capture's density`() {
        assertEquals(48.0, NodeProperties.pxToDp(126, 420)!!, 1e-9)
        assertEquals(100.0, NodeProperties.pxToDp(100, 160)!!, 1e-9)
    }

    @Test
    fun `an unknown or nonsensical density gives no dp rather than a guess`() {
        assertNull(NodeProperties.pxToDp(100, null))
        assertNull(NodeProperties.pxToDp(100, 0))
        assertNull(NodeProperties.pxToDp(100, -1))
    }

    @Test
    fun `dp sizes show one decimal, and none when whole`() {
        assertEquals("48", NodeProperties.formatDp(48.0))
        assertEquals("50.3", NodeProperties.formatDp(50.2857))
        assertEquals("379.4 × 50.3 dp at 420 dpi", NodeProperties.dpSize(996, 132, 420))
    }

    @Test
    fun `without a density the dp size says why it is missing`() {
        assertEquals("unknown: display density could not be read", NodeProperties.dpSize(996, 132, null))
    }

    @Test
    fun `properties come in identity, geometry and state, with blanks shown as a dash`() {
        val tree = UiTreeParser.parse(
            checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
        )
        val button = tree.nodes().first { it.testTag == "checkout_continue" }

        val sections = NodeProperties.of(button, 420)
        val values = sections.flatMap { it.rows }.associate { it.name to it.value }

        assertEquals(listOf("Identity", "Geometry", "State"), sections.map { it.title })
        assertEquals("android.view.View", values["Class"])
        assertEquals("checkout_continue", values["Test tag"])
        assertEquals("—", values["Text"])
        assertEquals("—", values["Content description"])
        assertEquals("com.example.compose:id/checkout_continue", values["Resource id"])
        assertEquals("[42,900][1038,1032]", values["Bounds"])
        assertEquals("996 × 132 px", values["Size"])
        assertEquals("379.4 × 50.3 dp at 420 dpi", values["Size in dp"])
        assertEquals("540, 966", values["Centre"])
        assertEquals("yes", values["Clickable"])
        assertEquals("yes, not focused", values["Focusable"])
        assertEquals("no", values["Checkable"])
    }

    @Test
    fun `geometry says where the node is relative to the viewport, when that is known`() {
        val tree = UiTreeParser.parse(
            checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
        )
        val button = tree.nodes().first { it.testTag == "checkout_continue" }
        fun viewport(visibility: NodeVisibility?) =
            NodeProperties.of(button, 420, visibility).single { it.title == "Geometry" }.rows
                .singleOrNull { it.name == "Viewport" }?.value

        assertEquals(null, viewport(null))
        assertEquals(
            "Within the viewport (occlusion not checked)",
            viewport(NodeVisibility(Presence.IN_VIEWPORT, button.bounds, 1.0)),
        )
        assertEquals(
            "In the tree but outside the viewport or its scroll container",
            viewport(NodeVisibility(Presence.OUTSIDE_VIEWPORT, null, 0.0)),
        )
    }
}
