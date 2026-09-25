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

    fun testADollarWrittenAsATemplateIsFoundAsTheConstantItIs() {
        // `${'$'}` is how a dollar sign is written in a raw string; the device shows "$5 total".
        val file = kotlin(
            "com/app/Price.kt",
            "package com.app\n\n" +
                "fun quoted() = Text(\"\${'\$'}5 total\")\n" +
                "fun raw() = Text(\"\"\"\${'\$'}5 total\"\"\")\n" +
                "fun template(amount: Int) = Text(\"\${'\$'}\$amount due\")\n",
        )

        val result = locate(SourceQuery(text = "\$5 total"))

        assertEquals(SourceTier.TEXT, result.tier)
        assertEquals(
            listOf(file.text.indexOf("\"\${'\$'}5 total\""), file.text.indexOf("\"\"\"\${'\$'}5")),
            result.hits.map { it.offset }.sorted(),
        )
        assertTrue(result.hits.all { it.pattern == null })

        // A real template beside a written dollar is still a template.
        val due = locate(SourceQuery(text = "\$7 due"))

        assertEquals(listOf(file.text.indexOf("\"\${'\$'}\$amount")), due.hits.map { it.offset })
        assertEquals("\${'\$'}\$amount due", due.best?.pattern)
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

    fun testTemplatedTestTagCallSiteIsFoundForTheRenderedTag() {
        val taps = kotlin(
            "com/app/Taps.kt",
            """
            package com.app

            fun forms(modifier: Modifier) = listOf("a", "b").map { form ->
                modifier.testTag("form_${'$'}{form}_button")
            }

            fun carousel(modifier: Modifier, row: Int) = modifier.testTag(tag = "carousel_${'$'}row")

            fun passed(modifier: Modifier, tag: String) = modifier.testTag(tag)
            """,
        )

        val form = locate(SourceQuery(testTag = "form_a_button"))

        assertEquals(SourceTier.TEST_TAG, form.tier)
        assertEquals(1, form.hits.size)
        val best = form.hits.single()
        assertTrue(best.exactContext)
        assertEquals(taps.text.indexOf("\"form_"), best.offset)
        assertEquals("form_\${form}_button", best.pattern)
        assertEquals("Taps.kt:4 · found by test tag pattern `form_\${form}_button`", SourceStatus.found(form))

        val carousel = locate(SourceQuery(testTag = "carousel_4"))

        assertEquals(taps.text.indexOf("\"carousel_"), carousel.best?.offset)
        assertEquals("carousel_\$row", carousel.best?.pattern)
    }

    fun testTemplatedTextIsFoundForTheRenderedText() {
        val feed = kotlin(
            "com/app/Feed.kt",
            """
            package com.app

            fun feed(row: Int) = Text("Feed row ${'$'}row")
            fun last(event: String) = Text("Last tap: ${'$'}event")
            fun loose(label: String, i: Int) = Text("${'$'}label ${'$'}i")
            """,
        )

        val row = locate(SourceQuery(text = "Feed row 1"))

        assertEquals(SourceTier.TEXT, row.tier)
        assertEquals(listOf(feed.text.indexOf("\"Feed row")), row.hits.map { it.offset })
        assertEquals("Feed row \$row", row.best?.pattern)

        // "nothing", the longest word, is the part the template filled in; the fixed words find it.
        val last = locate(SourceQuery(text = "Last tap: nothing yet"))

        assertEquals(feed.text.indexOf("\"Last tap"), last.best?.offset)
    }

    fun testAnExactLiteralRanksAboveATemplate() {
        val file = kotlin(
            "com/app/Carousel.kt",
            """
            package com.app

            fun carousel(m: Modifier, row: Int) = m.testTag("carousel_${'$'}row")
            fun pinned(m: Modifier) = m.testTag("carousel_4")
            fun feed(row: Int) = Text("Feed row ${'$'}row")
            fun first() = Text("Feed row 1")
            """,
        )

        val tag = locate(SourceQuery(testTag = "carousel_4"))

        assertEquals(2, tag.hits.size)
        assertEquals(file.text.indexOf("\"carousel_4\""), tag.best?.offset)
        assertNull(tag.best?.pattern)
        assertEquals("carousel_\$row", tag.hits[1].pattern)

        val text = locate(SourceQuery(text = "Feed row 1"))

        assertEquals(2, text.hits.size)
        assertEquals(file.text.indexOf("\"Feed row 1\""), text.best?.offset)
        assertEquals("Feed row \$row", text.hits[1].pattern)
    }

    fun testAStringsXmlFormatIsFoundForTheRenderedText() {
        xml(
            "res/values/strings.xml",
            """
            <resources>
                <string name="taps_count">Tapped %1${'$'}d times</string>
            </resources>
            """,
        )
        val code = kotlin(
            "com/app/Count.kt",
            """
            package com.app

            fun count(n: Int) = stringResource(R.string.taps_count, n)
            """,
        )

        val result = locate(SourceQuery(text = "Tapped 3 times"))

        assertEquals(SourceTier.STRING_RESOURCE, result.tier)
        assertEquals(code.text.indexOf("taps_count, n"), result.best?.offset)
        assertEquals("Tapped %1\$d times", result.best?.pattern)
    }

    fun testAnElementWithNothingOfItsOwnBorrowsFromItsLabel() {
        val file = kotlin(
            "com/app/Form.kt",
            """
            package com.app

            fun form() = Button(onClick = {}) { Text("Save changes") }
            """,
        )
        val label = uiNode(text = "Save changes")
        val button = uiNode(children = listOf(label, uiNode(className = "android.widget.Button")))
        val tree = UiTree(button, UiFramework.COMPOSE, UiTree.TestTagSupport.AVAILABLE)
        val query = SourceQuery.of(button, UiFramework.COMPOSE)

        val result = ReadAction.compute<SourceResult, RuntimeException> {
            SourceLocator(project).locate(query, "com.app", SourceRelatives.of(tree, button), activity = null)
        }

        assertTrue(query.isEmpty)
        assertEquals(SourceTier.TEXT, result.tier)
        assertSame(label, result.via?.node)
        assertEquals(file.text.indexOf("\"Save changes\""), result.best?.offset)
        assertEquals(
            "Form.kt:3 · found by text via its label 'Save changes' — may be one of several",
            SourceStatus.found(result),
        )
    }

    fun testNothingFoundFallsBackToTheScreensActivity() {
        val screen = kotlin(
            "com/app/MainActivity.kt",
            """
            package com.app

            class MainActivity
            """,
        )
        val nowhere = SourceQuery(text = "Nowhere at all")
        val also = SourceQuery(text = "Also nowhere")
        val relative = SourceRelative(uiNode(text = "Also nowhere"), SourceRelative.Kind.ANCESTOR, also)

        val result = ReadAction.compute<SourceResult, RuntimeException> {
            SourceLocator(project).locate(nowhere, "com.app", listOf(relative), "com.app.MainActivity")
        }

        assertEquals(SourceTier.ACTIVITY, result.tier)
        assertEquals(screen.text.indexOf("MainActivity"), result.best?.offset)
        assertEquals("com.app.MainActivity", result.activity)
        assertEquals(1, result.relativesTried)

        val elsewhere = ReadAction.compute<SourceResult, RuntimeException> {
            SourceLocator(project).locate(nowhere, "com.app", emptyList(), "com.other.Gone")
        }

        assertNull(elsewhere.tier)
        assertTrue(elsewhere.hits.isEmpty())
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

    private fun uiNode(
        text: String = "",
        className: String = "android.view.View",
        children: List<UiNode> = emptyList(),
    ) = UiNode(
            className = className,
            packageName = "com.app",
            text = text,
            contentDescription = "",
            resourceId = "",
            bounds = UiNode.Bounds(0, 0, 100, 100),
            clickable = children.isNotEmpty(),
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
