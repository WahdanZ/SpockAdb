package spock.adb.uitree

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
}
