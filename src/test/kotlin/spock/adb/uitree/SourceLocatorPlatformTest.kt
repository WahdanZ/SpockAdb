package spock.adb.uitree

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [SourceLocator] against real PSI and the real word index, in a light in-memory project.
 *
 * The only platform test in the suite: everything else about source navigation is pure logic in
 * [SourceRankingTest]. This one checks what that cannot — that each tier's search finds the offset
 * it should in Kotlin, Java and XML, and nothing else.
 */
class SourceLocatorPlatformTest : BasePlatformTestCase() {

    fun testTagPassedToTestTagOutranksTheSameLiteralElsewhere() {
        val screen = kotlin(
            "com/app/Screen.kt",
            """
            package com.app

            const val LOGGED = "checkout_button"

            fun screen(modifier: Any) = tagged(modifier, "checkout_button")

            fun tagged(m: Any, tag: String) = testTag("checkout_button")
            fun testTag(tag: String) = tag
            """,
        )

        val result = locate(SourceQuery(testTag = "checkout_button"))

        assertEquals(SourceTier.TEST_TAG, result.tier)
        assertEquals(3, result.hits.size)
        val best = result.hits.first()
        assertTrue(best.exactContext)
        assertEquals(screen.text.indexOf("testTag(\"checkout_button\")") + "testTag(".length, best.offset)
        assertEquals("Screen.kt", best.fileName)
    }

    fun testSubstringsAndTemplatesAreNotMatches() {
        kotlin(
            "com/app/Labels.kt",
            """
            package com.app

            val a = "Submitted"
            val b = "Submit later"
            fun c(n: Int) = "Submit ${'$'}n"
            """,
        )

        val result = locate(SourceQuery(text = "Submit"))

        assertNull(result.tier)
        assertTrue(result.hits.isEmpty())
    }

    fun testJavaLiteralIsFoundByText() {
        val file = java(
            "com/app/Legacy.java",
            """
            package com.app;

            class Legacy {
                String label() { return "Say \"hi\""; }
            }
            """,
        )

        val result = locate(SourceQuery(text = "Say \"hi\""))

        assertEquals(SourceTier.TEXT, result.tier)
        assertEquals(file.text.indexOf("\"Say"), result.best?.offset)
        assertEquals("com.app", result.best?.packageName)
    }

    fun testViewIdIsItsLayoutDeclarationThenItsCodeUsages() {
        val layout = xml(
            "res/layout/screen.xml",
            """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/total_label" android:layout_width="wrap_content" />
                <TextView android:layout_below="@id/total_label" />
            </LinearLayout>
            """,
        )
        val code = kotlin(
            "com/app/Total.kt",
            """
            package com.app

            fun bind(view: Any) = find(R.id.total_label)
            fun unrelated() = find(android.R.id.total_label)
            // R.id.total_label in a comment is not a use
            """,
        )

        val result = locate(SourceQuery(viewId = "total_label"))

        assertEquals(SourceTier.VIEW_ID, result.tier)
        assertEquals(2, result.hits.size)
        assertEquals("screen.xml", result.hits[0].fileName)
        // An attribute value's offset is inside its quotes.
        assertEquals(layout.text.indexOf("@+id/total_label"), result.hits[0].offset)
        assertEquals(code.text.indexOf("total_label)"), result.hits[1].offset)
    }

    fun testTextFromStringsXmlLeadsToWhereTheStringIsUsed() {
        xml(
            "res/values/strings.xml",
            """
            <resources>
                <string name="kept_label">Kept in a resource file</string>
                <string name="other">Kept elsewhere</string>
            </resources>
            """,
        )
        val code = kotlin(
            "com/app/Kept.kt",
            """
            package com.app

            fun kept() = stringResource(R.string.kept_label)
            """,
        )

        val result = locate(SourceQuery(text = "Kept in a resource file"))

        assertEquals(SourceTier.STRING_RESOURCE, result.tier)
        assertEquals(code.text.indexOf("kept_label)"), result.best?.offset)
    }

    fun testAnUnusedStringFallsBackToItsEntry() {
        val strings = xml(
            "res/values/strings.xml",
            """
            <resources>
                <string name="orphan">Nobody &amp; nothing</string>
            </resources>
            """,
        )

        val result = locate(SourceQuery(contentDescription = "Nobody & nothing"))

        assertEquals(SourceTier.STRING_RESOURCE, result.tier)
        assertEquals(strings.text.indexOf("<string"), result.best?.offset)
    }

    fun testTheFileThatBuildsTheRestOfTheScreenRanksFirst() {
        kotlin("com/app/a/Settings.kt", "package com.app.a\n\nfun settings() = listOf(\"Continue\", \"Sign out\")\n")
        val checkout = kotlin(
            "com/app/b/Checkout.kt",
            "package com.app.b\n\nfun checkout() = listOf(\"Continue\", \"Order total\", \"card_number\")\n",
        )

        val result = ReadAction.compute<SourceResult, RuntimeException> {
            SourceLocator(project, screen = setOf("Order total", "card_number"))
                .locate(SourceQuery(text = "Continue"), "com.app")
        }

        assertEquals(2, result.hits.size)
        assertEquals("Checkout.kt", result.best?.fileName)
        assertEquals(checkout.text.indexOf("\"Continue\""), result.best?.offset)
    }

    fun testNothingFoundReportsNoTier() {
        kotlin("com/app/Empty.kt", "package com.app\n\nval unrelated = \"something else\"\n")

        val result = locate(SourceQuery(testTag = "missing_tag", text = "Missing text"))

        assertNull(result.tier)
        assertTrue(result.hits.isEmpty())
    }

    private fun locate(query: SourceQuery): SourceResult =
        ReadAction.compute<SourceResult, RuntimeException> { SourceLocator(project).locate(query, "com.app") }

    private fun kotlin(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text.trimIndent())
    private fun java(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text.trimIndent())
    private fun xml(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text.trimIndent())
}
