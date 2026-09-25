package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessibilityAuditTest {

    private val composeTree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
    )

    @Test
    fun `automation tags do not count as spoken labels`() {
        val target = composeTree.nodes().first { it.clickable }.copy(
            text = "",
            contentDescription = "",
            resourceId = "only_a_test_tag",
            children = emptyList(),
        )
        val findings = AccessibilityAudit.audit(composeTree.copy(root = target))
        assertTrue(findings.any { it.issue.contains("no accessible text") })
    }

    @Test
    fun `child text labels a clickable parent without using its tag`() {
        val child = composeTree.nodes().first { it.text.isNotBlank() }.copy(
            text = "Continue",
            clickable = false,
            children = emptyList(),
        )
        val target = child.copy(text = "", clickable = true, children = listOf(child))
        val findings = AccessibilityAudit.audit(composeTree.copy(root = target))
        assertTrue(findings.none { it.issue.contains("no accessible text") })
    }

    @Test
    fun `a tagged scroll container holding labelled rows is not an unlabelled control`() {
        val row = composeTree.nodes().first { it.clickable }.copy(
            text = "Order #1",
            contentDescription = "",
            scrollable = false,
            children = emptyList(),
        )
        val list = row.copy(
            text = "",
            resourceId = "orders_list",
            clickable = false,
            scrollable = true,
            children = listOf(row, row.copy(text = "Order #2")),
        )
        val findings = AccessibilityAudit.audit(composeTree.copy(root = list))
        assertTrue(findings.none { it.issue.contains("no accessible text") }, findings.toString())

        // Scrolling does not exempt a container that can also be tapped on its own.
        val clickableList = list.copy(clickable = true)
        val clickableFindings = AccessibilityAudit.audit(composeTree.copy(root = clickableList))
        assertTrue(clickableFindings.any { it.issue.contains("no accessible text") && it.node === clickableList })
    }

    @Test
    fun `touch target threshold follows effective density`() {
        val target = composeTree.nodes().first { it.clickable }.copy(
            bounds = UiNode.Bounds(0, 0, 96, 96),
            children = emptyList(),
        )
        fun small(dpi: Int?) = AccessibilityAudit.audit(composeTree.copy(root = target, densityDpi = dpi))
            .any { it.issue.contains("smaller than") }
        assertTrue(!small(320), "96px is 48dp at 320dpi")
        assertTrue(small(480), "96px is only 32dp at 480dpi")
        assertTrue(!small(null), "Unknown density must not be guessed")
        assertTrue(AccessibilityAudit.coverageNote(composeTree).contains("skipped"))
    }

    @Test
    fun `flags an interactive element with nothing to announce`() {
        val findings = AccessibilityAudit.audit(composeTree)

        assertTrue(
            findings.any { it.issue.contains("no accessible text or content description") },
            findings.map { it.issue }.toString(),
        )
    }

    @Test
    fun `flags a touch target below the recommended minimum`() {
        // The fixture has a 30x30px clickable node.
        val findings = AccessibilityAudit.audit(composeTree.copy(densityDpi = 160))
        assertTrue(findings.any { it.issue.contains("smaller than the recommended minimum") })
    }

    @Test
    fun `suggests a Compose fix for a Compose screen and a View fix for Views`() {
        val finding = AccessibilityAudit.audit(composeTree).first {
            it.issue.contains("no accessible text or content description")
        }

        assertTrue(finding.describe(UiFramework.COMPOSE).contains("Modifier.semantics"))
        assertTrue(finding.describe(UiFramework.VIEWS).contains("android:contentDescription"))
    }

    @Test
    fun `a well-labelled screen produces no unlabelled-control findings`() {
        val labelled = UiTreeParser.parse(
            """
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="" class="androidx.compose.ui.platform.AndroidComposeView"
                    package="p" content-desc="" checkable="false" checked="false" clickable="false"
                    enabled="true" focusable="false" focused="false" scrollable="false"
                    long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2154]">
                <node index="0" text="Continue" resource-id="p:id/go" class="android.view.View" package="p"
                      content-desc="" checkable="false" checked="false" clickable="true" enabled="true"
                      focusable="true" focused="false" scrollable="false" long-clickable="false"
                      password="false" selected="false" bounds="[42,900][1038,1032]" />
              </node>
            </hierarchy>
            """.trimIndent(),
        )

        val findings = AccessibilityAudit.audit(labelled)
        assertTrue(
            findings.none { it.issue.contains("no accessible text or content description") },
            findings.toString(),
        )
    }

    /**
     * The sample's Audit tab as an API 34 emulator dumped it at 480dpi, trimmed: row 5 of the list is
     * half scrolled out, so `uiautomator` cut its bounds short and left its "Option 5" text out.
     */
    private val auditTab = UiTreeParser.parse(
        """
        <hierarchy rotation="0">
          <node class="android.widget.FrameLayout" package="p" bounds="[0,0][1080,2636]">
            <node class="androidx.compose.ui.platform.ComposeView" package="p" bounds="[0,264][1080,2564]">
              <node class="android.view.View" resource-id="audit_unlabelled" package="p" clickable="true"
                    enabled="true" bounds="[72,714][216,858]" />
              <node class="android.view.View" resource-id="audit_small_target" content-desc="Dismiss" package="p"
                    clickable="true" enabled="true" bounds="[228,750][300,822]" />
              <node class="android.view.View" resource-id="audit_list" package="p" scrollable="true"
                    enabled="true" bounds="[72,870][1008,1590]">
                ${row(1, 870)}${row(2, 1038)}${row(3, 1206)}${row(4, 1374)}
                <node class="android.view.View" package="p" clickable="true" enabled="true"
                      bounds="[72,1542][1008,1662]" />
              </node>
            </node>
          </node>
        </hierarchy>
        """.trimIndent(),
    ).copy(densityDpi = 480)

    private fun row(number: Int, top: Int) = """
        <node class="android.view.View" package="p" clickable="true" enabled="true" bounds="[72,$top][1008,${top + 168}]">
          <node class="android.widget.TextView" text="Option $number" package="p" bounds="[108,${top + 48}][302,${top + 120}]" />
        </node>
    """

    @Test
    fun `a row cut off at its list's edge is neither too small nor unlabelled`() {
        val findings = AccessibilityAudit.audit(auditTab)

        assertEquals(
            listOf("audit_unlabelled", "audit_small_target"),
            findings.map { it.node.testTag },
            findings.joinToString("\n") { it.issue },
        )
        assertTrue(findings.none { it.node.bounds == UiNode.Bounds(72, 1542, 1008, 1662) })
    }

    @Test
    fun `the coverage note counts what was skipped as cut off`() {
        val note = AccessibilityAudit.coverageNote(auditTab)

        assertTrue(note.contains("Skipped 1 control(s) at a scroll container's edge"), note)
        assertTrue(note.contains("checked when fully in view"), note)
        assertFalse(AccessibilityAudit.coverageNote(composeTree).contains("Skipped"), "nothing to skip there")
    }

    @Test
    fun `a complete small target is still flagged, and so is a complete control out of view`() {
        val findings = AccessibilityAudit.audit(auditTab)
        assertTrue(findings.any { it.node.testTag == "audit_small_target" && it.issue.contains("72x72px at 480dpi") })

        // Laid out below the list rather than cut at its edge: its bounds are whole, so they are checked.
        val list = auditTab.nodes().first { it.testTag == "audit_list" }
        val offScreen = list.children.last().copy(bounds = UiNode.Bounds(72, 1900, 1008, 1960))
        val withOffScreen = list.copy(children = list.children + offScreen)
        val moved = auditTab.copy(root = replace(auditTab.root!!, list, withOffScreen))
        val offScreenFindings = AccessibilityAudit.audit(moved).filter { it.node === offScreen }

        assertTrue(offScreenFindings.any { it.issue.contains("no accessible text") }, offScreenFindings.toString())
        assertTrue(offScreenFindings.any { it.issue.contains("smaller than") }, offScreenFindings.toString())
    }

    @Test
    fun `the viewport passed in decides what counts as cut off`() {
        // A display ending at y=1500 moves the cut to row 4, which is labelled and large enough, and
        // leaves row 5 wholly out of view rather than at an edge — so it is checked like any control.
        val viewport = UiNode.Bounds(0, 0, 1080, 1500)
        val row5 = UiNode.Bounds(72, 1542, 1008, 1662)

        assertFalse(AccessibilityAudit.coverageNote(auditTab, viewport).contains("Skipped"))
        assertTrue(AccessibilityAudit.audit(auditTab, viewport).any { it.node.bounds == row5 })
    }

    private fun replace(node: UiNode, old: UiNode, new: UiNode): UiNode = when {
        node === old -> new
        else -> node.copy(children = node.children.map { replace(it, old, new) })
    }
}
