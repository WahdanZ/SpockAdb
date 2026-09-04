package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessibilityAuditTest {

    private val composeTree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/compose-material3.xml")).bufferedReader().readText(),
    )

    @Test
    fun `flags an interactive element with nothing to announce`() {
        val findings = AccessibilityAudit.audit(composeTree)

        assertTrue(
            findings.any { it.issue.contains("no text, content description or test tag") },
            findings.map { it.issue }.toString(),
        )
    }

    @Test
    fun `flags a touch target below the recommended minimum`() {
        // The fixture has a 30x30px clickable node.
        val findings = AccessibilityAudit.audit(composeTree)
        assertTrue(findings.any { it.issue.contains("smaller than the recommended minimum") })
    }

    @Test
    fun `suggests a Compose fix for a Compose screen and a View fix for Views`() {
        val finding = AccessibilityAudit.audit(composeTree).first {
            it.issue.contains("no text, content description")
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
        assertTrue(findings.none { it.issue.contains("no text, content description") }, findings.toString())
    }
}
