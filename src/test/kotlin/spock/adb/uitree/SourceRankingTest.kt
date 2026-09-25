package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The part of "Jump to Source" that needs no IDE: what an element can be searched by, in which
 * order, how a literal or a `strings.xml` value is read, how matches are ranked, and what is said.
 */
class SourceRankingTest {

    private fun dump(name: String): UiTree = UiTreeParser.parse(
        checkNotNull(javaClass.getResourceAsStream("/uidumps/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText(),
    )

    // ---------------------------------------------------------------- what is searched

    @Test
    fun `a bare id is a test tag published whole by testTagsAsResourceId, and nothing else`() {
        val query = SourceQuery.of(node(resourceId = "submit_button"), UiFramework.COMPOSE)

        assertEquals("submit_button", query.testTag)
        assertNull(query.viewId)
        assertEquals(SourceTier.TEST_TAG, query.steps.first().tier)
    }

    @Test
    fun `a package-qualified id on a Compose screen is tried as a tag, then as a view id`() {
        val tree = dump("compose-material3.xml")
        val button = tree.nodes().first { it.resourceId == "com.example.compose:id/checkout_continue" }

        val query = SourceQuery.of(button, tree.framework)

        assertEquals("checkout_continue", query.testTag)
        assertEquals("checkout_continue", query.viewId)
        assertEquals(listOf(SourceTier.TEST_TAG, SourceTier.VIEW_ID), query.steps.map { it.tier }.take(2))
    }

    @Test
    fun `on a Views screen an id is a View id, not a test tag`() {
        val tree = dump("views-navigation-fragment.xml")
        val text = tree.nodes().first { it.resourceId == "com.example.myapplication:id/text_home" }

        val query = SourceQuery.of(text, tree.framework)

        assertNull(query.testTag)
        assertEquals("text_home", query.viewId)
        assertEquals(SourceTier.VIEW_ID, query.steps.first().tier)
    }

    @Test
    fun `resource ids parse as a view id, a bare tag, or nothing`() {
        assertEquals(ResourceIdRef.ViewId("com.app", "save_button"), ResourceIdRef.parse("com.app:id/save_button"))
        assertEquals(ResourceIdRef.Tag("save_button"), ResourceIdRef.parse("save_button"))
        // A tag may contain a slash; without ":id/" it is still a tag, whole.
        assertEquals(ResourceIdRef.Tag("screen/save"), ResourceIdRef.parse("screen/save"))
        assertNull(ResourceIdRef.parse(""))
        assertNull(ResourceIdRef.parse("android:id/content"), "the framework's ids are declared by no app")
        assertNull(ResourceIdRef.parse("com.app:id/"))
    }

    @Test
    fun `steps run tag, id, text, description, then class — only those the element has`() {
        val query = SourceQuery(
            testTag = "t",
            viewId = null,
            text = "Hello",
            contentDescription = "Greeting",
            customClass = "com.app.BadgeView",
        )

        assertEquals(
            listOf(SourceTier.TEST_TAG, SourceTier.TEXT, SourceTier.CONTENT_DESCRIPTION, SourceTier.CLASS),
            query.steps.map { it.tier },
        )
        assertEquals(listOf("t", "Hello", "Greeting", "com.app.BadgeView"), query.steps.map { it.value })
    }

    @Test
    fun `blank text and description are not searched, and a bare element has nothing to search`() {
        val query = SourceQuery.of(node(text = "  ", contentDescription = ""), UiFramework.COMPOSE)

        assertTrue(query.isEmpty)
    }

    @Test
    fun `framework and Jetpack classes are never looked up, but an app's own class is, nested with dots`() {
        assertNull(SourceMatching.customClassName("android.widget.TextView"))
        assertNull(SourceMatching.customClassName("android.view.View"))
        assertNull(SourceMatching.customClassName("androidx.recyclerview.widget.RecyclerView"))
        assertNull(SourceMatching.customClassName("com.google.android.material.button.MaterialButton"))
        assertNull(SourceMatching.customClassName("com.android.internal.widget.ActionBarContainer"))
        assertNull(SourceMatching.customClassName("TextView"), "no package, nothing to look up")
        assertNull(SourceMatching.customClassName(""))
        assertEquals("com.app.BadgeView", SourceMatching.customClassName("com.app.BadgeView"))
        assertEquals("com.app.Screen.BadgeView", SourceMatching.customClassName("com.app.Screen\$BadgeView"))
    }

    // ---------------------------------------------------------------- reading source text

    @Test
    fun `the index is searched by the value's longest word first`() {
        assertEquals("checkout_continue", SourceTemplate.searchWords("checkout_continue").firstOrNull())
        assertEquals("resource", SourceTemplate.searchWords("Kept in a resource file").firstOrNull())
        assertEquals("Größe", SourceTemplate.searchWords("Größe: 3").firstOrNull())
        assertNull(SourceTemplate.searchWords("→ …").firstOrNull())
    }

    @Test
    fun `a literal's value is exact, not a substring`() {
        assertEquals("Submit", SourceMatching.literalValue("\"Submit\"", kotlin = true))
        assertTrue(SourceMatching.literalValue("\"Submitted\"", kotlin = true) != "Submit")
    }

    @Test
    fun `escapes are decoded before comparing`() {
        assertEquals("Say \"hi\"", SourceMatching.literalValue("\"Say \\\"hi\\\"\"", kotlin = true))
        assertEquals("a\\b", SourceMatching.literalValue("\"a\\\\b\"", kotlin = false))
        assertEquals("one\ntwo", SourceMatching.literalValue("\"one\\ntwo\"", kotlin = true))
        assertEquals("é", SourceMatching.literalValue("\"\\u00e9\"", kotlin = false))
        assertEquals("\$5", SourceMatching.literalValue("\"\\\$5\"", kotlin = true))
    }

    @Test
    fun `a Kotlin template is only known at run time, so it matches nothing`() {
        assertNull(SourceMatching.literalValue("\"Hello, \$name\"", kotlin = true))
        assertNull(SourceMatching.literalValue("\"Item \${index + 1}\"", kotlin = true))
        // Not a template: a lone dollar, or an escaped one.
        assertEquals("Costs \$ 5", SourceMatching.literalValue("\"Costs \$ 5\"", kotlin = true))
        // In Java a dollar is just a dollar.
        assertEquals("Hello, \$name", SourceMatching.literalValue("\"Hello, \$name\"", kotlin = false))
    }

    @Test
    fun `a dollar written as a template is a constant, as the template matcher reads it`() {
        assertEquals("\$5 total", SourceMatching.literalValue("\"\${'$'}5 total\"", kotlin = true))
        assertEquals("\$5 total", SourceMatching.literalValue("\"\"\"\${'$'}5 total\"\"\"", kotlin = true))
        assertNull(SourceMatching.literalValue("\"\${'$'}\$amount total\"", kotlin = true))
        // `$$` opens no template in Kotlin: a dollar needs a name or a brace after it.
        assertEquals("\$\$", SourceMatching.literalValue("\"\$\$\"", kotlin = true))
    }

    @Test
    fun `a word is found whole, not inside a longer one`() {
        assertEquals(listOf(0, 13), SourceMatching.wordOffsets("row = listOf(row, rows, _row, row2)", "row"))
        assertEquals(listOf(9), SourceMatching.wordOffsets("R.id.foo.total_label", "total_label"))
        assertEquals(listOf(6), SourceMatching.wordOffsets("\"one\\ntwo\"", "two"), "after an escape")
        assertEquals(listOf(9), SourceMatching.wordOffsets("\"Tapped \$count\"", "count"))
        assertEquals(emptyList<Int>(), SourceMatching.wordOffsets("anything", ""))
    }

    @Test
    fun `a Kotlin raw string is taken as written, and a Java text block is not attempted`() {
        assertEquals("C:\\path\\n", SourceMatching.literalValue("\"\"\"C:\\path\\n\"\"\"", kotlin = true))
        assertNull(SourceMatching.literalValue("\"\"\"\n    block\n    \"\"\"", kotlin = false))
        assertNull(SourceMatching.literalValue("\"\"\"raw \$name\"\"\"", kotlin = true))
    }

    @Test
    fun `something that is not a string literal has no value`() {
        assertNull(SourceMatching.literalValue("'c'", kotlin = true))
        assertNull(SourceMatching.literalValue("\"", kotlin = true))
        assertNull(SourceMatching.literalValue("name", kotlin = true))
    }

    @Test
    fun `strings xml values are read as Android shows them`() {
        assertEquals("Kept in a resource file", SourceMatching.androidStringValue("Kept in a resource file"))
        assertEquals("Don't stop", SourceMatching.androidStringValue("Don\\'t stop"))
        assertEquals("Tom & Jerry", SourceMatching.androidStringValue("Tom &amp; Jerry"))
        assertEquals("Bold move", SourceMatching.androidStringValue("<b>Bold</b> move"))
        assertEquals("one two", SourceMatching.androidStringValue("\n    one\n    two\n"))
        assertEquals("  spaced  ", SourceMatching.androidStringValue("\"  spaced  \""))
        assertEquals("line\nbreak", SourceMatching.androidStringValue("line\\nbreak"))
        assertEquals("<tag> & more", SourceMatching.androidStringValue("<![CDATA[<tag>]]> &amp; <i>more</i>"))
        assertEquals("A\u00e9", SourceMatching.androidStringValue("A&#233;"))
        assertEquals("@home", SourceMatching.androidStringValue("\\@home"))
    }

    @Test
    fun `a literal passed to testTag is its declaration`() {
        assertTrue(SourceMatching.isTestTagArgument("Modifier.testTag("))
        assertTrue(SourceMatching.isTestTagArgument("Modifier\n    .padding(8.dp)\n    .testTag( "))
        assertTrue(SourceMatching.isTestTagArgument("testTag(tag = "))
        assertFalse(SourceMatching.isTestTagArgument("Text("))
        assertFalse(SourceMatching.isTestTagArgument("val myTestTag("))
        assertFalse(SourceMatching.isTestTagArgument("testTag(\"a\") + Text("))
    }

    @Test
    fun `R references are the app's R, qualified or not, never android R`() {
        assertTrue(SourceMatching.isResourceReference("id = R.id.", "id"))
        assertTrue(SourceMatching.isResourceReference("findViewById(R.id.", "id"))
        assertTrue(SourceMatching.isResourceReference("spock.adb.sample.R.id.", "id"))
        assertTrue(SourceMatching.isResourceReference("stringResource(R.string.", "string"))
        assertTrue(SourceMatching.isResourceReference("R\n        .string\n        .", "string"))
        assertFalse(SourceMatching.isResourceReference("android.R.id.", "id"))
        assertFalse(SourceMatching.isResourceReference("R.string.", "id"))
        assertFalse(SourceMatching.isResourceReference("MyR.id.", "id"))
        assertFalse(SourceMatching.isResourceReference("R.id.other + ", "id"))
    }

    @Test
    fun `android id declares with or without a plus`() {
        assertEquals("nav_host", SourceMatching.declaredIdName("@+id/nav_host"))
        assertEquals("nav_host", SourceMatching.declaredIdName("@id/nav_host"))
        assertNull(SourceMatching.declaredIdName("@android:id/list"))
        assertNull(SourceMatching.declaredIdName("@string/nav_host"))
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `the element's own declaration outranks a mention`() {
        val mention = hit("Screen.kt", offset = 10)
        val declaration = hit("Tags.kt", offset = 500, exactContext = true)

        val ranked = SourceRanking.rank(listOf(mention, declaration), windowPackage = null)

        assertEquals(listOf(declaration, mention), ranked)
    }

    @Test
    fun `a literal equal to the value outranks a template that renders to it`() {
        val template = hit("A.kt", offset = 5, exactContext = true, pattern = "carousel_\$row")
        val literal = hit("B.kt", offset = 500, exactContext = true)
        val mention = hit("C.kt")

        val ranked = SourceRanking.rank(listOf(template, mention, literal), windowPackage = null)

        assertEquals(listOf(literal, template, mention), ranked)
    }

    @Test
    fun `then the module most matches are in`() {
        val lonely = hit("A.kt", module = "legacy")
        val app1 = hit("B.kt", module = "app")
        val app2 = hit("C.kt", module = "app")

        val ranked = SourceRanking.rank(listOf(lonely, app1, app2), windowPackage = null)

        assertEquals(listOf(app1, app2, lonely), ranked)
    }

    @Test
    fun `then the file whose package shares most with the captured window`() {
        val other = hit("Other.kt", packageName = "com.other.ui")
        val app = hit("Home.kt", packageName = "com.app.ui.home")

        val ranked = SourceRanking.rank(listOf(other, app), windowPackage = "com.app.debug")

        assertEquals(listOf(app, other), ranked)
        assertEquals(2, SourceRanking.packageAffinity("com.app.ui", "com.app.debug"))
        assertEquals(0, SourceRanking.packageAffinity(null, "com.app"))
        assertEquals(0, SourceRanking.packageAffinity("com.app", null))
    }

    @Test
    fun `then the file that builds more of the rest of the screen`() {
        val views = hit("A.kt", screenAffinity = 0)
        val compose = hit("B.kt", screenAffinity = 3)

        assertEquals(listOf(compose, views), SourceRanking.rank(listOf(views, compose), windowPackage = null))
    }

    @Test
    fun `the rest of the screen is its other tags and texts, long enough to mean something`() {
        val submit = node(text = "Submit")
        val tree = UiTree(
            root = node(text = "OK").copy(
                children = listOf(submit, node(resourceId = "name_field"), node(contentDescription = "Back")),
            ),
            framework = UiFramework.COMPOSE,
            testTagSupport = UiTree.TestTagSupport.AVAILABLE,
        )

        assertEquals(setOf("name_field", "Back"), SourceRanking.screenValues(tree, submit))
        assertEquals(emptySet<String>(), SourceRanking.screenValues(null, submit))
    }

    @Test
    fun `screen affinity counts whole quoted values, in code or XML`() {
        val code = "Text(\"Back\")\nModifier.testTag(\"name_field\")\nText(\"Backstage\")"

        assertEquals(2, SourceRanking.screenAffinity(code, setOf("Back", "name_field", "Other")))
        assertEquals(1, SourceRanking.screenAffinity("<string name=\"b\">Back</string>", setOf("Back")))
        assertEquals(0, SourceRanking.screenAffinity("Text(\"Backstage\")", setOf("Back")))
    }

    @Test
    fun `the same place found twice is listed once, and ties keep file then offset order`() {
        val first = hit("A.kt", offset = 5)
        val second = hit("A.kt", offset = 50)

        val ranked = SourceRanking.rank(listOf(second, first, second), windowPackage = null)

        assertEquals(listOf(first, second), ranked)
    }

    // ---------------------------------------------------------------- what is said

    @Test
    fun `a match names its file, line and the identifier that found it`() {
        val result = SourceResult(SourceQuery(testTag = "t"), SourceTier.TEST_TAG, listOf(hit("Screen.kt", line = 42)))

        assertEquals("Screen.kt:42 · found by test tag", SourceStatus.found(result))
    }

    @Test
    fun `a match by text says it may be one of several`() {
        val one = SourceResult(SourceQuery(text = "Hi"), SourceTier.TEXT, listOf(hit("Screen.kt", line = 7)))
        val two = one.copy(hits = listOf(hit("Screen.kt", line = 7), hit("Other.kt", line = 9)))
        val resource = one.copy(tier = SourceTier.STRING_RESOURCE, hits = listOf(hit("Screen.kt", line = 3)))

        assertEquals("Screen.kt:7 · found by text — may be one of several", SourceStatus.found(one))
        assertEquals("Screen.kt:7 · found by text · best of 2", SourceStatus.found(two))
        assertEquals("Screen.kt:3 · found by text in strings.xml — may be one of several", SourceStatus.found(resource))
    }

    @Test
    fun `a match through a template names the template`() {
        val tag = SourceResult(
            SourceQuery(testTag = "form_a_button"),
            SourceTier.TEST_TAG,
            listOf(hit("Taps.kt", line = 243, pattern = "form_\${form}_button")),
        )
        val text = SourceResult(
            SourceQuery(text = "Feed row 1"),
            SourceTier.TEXT,
            listOf(hit("Feed.kt", line = 303, pattern = "Feed row \$row")),
        )

        assertEquals("Taps.kt:243 · found by test tag pattern `form_\${form}_button`", SourceStatus.found(tag))
        assertEquals(
            "Feed.kt:303 · found by text pattern `Feed row \$row` — may be one of several",
            SourceStatus.found(text),
        )
    }

    @Test
    fun `a match through a relative says whose identifier found it`() {
        val label = SourceRelative(node(text = "Save"), SourceRelative.Kind.LABEL, SourceQuery(text = "Save"))
        val section = SourceRelative(node(resourceId = "feed_section"), SourceRelative.Kind.ANCESTOR, SourceQuery())
        val byLabel = SourceResult(SourceQuery(), SourceTier.TEXT, listOf(hit("Taps.kt", line = 244)), via = label)
        val bySection =
            SourceResult(SourceQuery(), SourceTier.TEST_TAG, listOf(hit("Feed.kt", line = 300)), via = section)

        assertEquals(
            "Taps.kt:244 · found by text via its label 'Save' — may be one of several",
            SourceStatus.found(byLabel),
        )
        assertEquals("Feed.kt:300 · found by test tag via enclosing 'feed_section'", SourceStatus.found(bySection))
        assertEquals("test tag via enclosing 'feed_section'", SourceStatus.how(bySection))
    }

    @Test
    fun `with nothing found but the screen's activity, it says so, and whether it opened it`() {
        val result = SourceResult(
            SourceQuery(),
            SourceTier.ACTIVITY,
            listOf(hit("ComposeReliabilityActivity.kt", line = 107)),
            activity = "spock.adb.sample.compose.ComposeReliabilityActivity",
            relativesTried = 2,
        )

        assertEquals(
            "No match for this element; opened the screen's activity `ComposeReliabilityActivity`",
            SourceStatus.found(result, opened = true),
        )
        assertEquals(
            "No match for this element; Jump to Source opens the screen's activity `ComposeReliabilityActivity`",
            SourceStatus.found(result),
        )
    }

    @Test
    fun `nothing found counts the relatives that were searched for too`() {
        val bare = SourceResult(SourceQuery(), null, emptyList(), relativesTried = 2)
        val tagged = SourceResult(SourceQuery(testTag = "x"), null, emptyList(), relativesTried = 1)
        val alone = SourceResult(SourceQuery(testTag = "x"), null, emptyList())

        assertEquals(
            "No source found for this element, nor for its 2 nearest identifiable elements, in this project.",
            SourceStatus.notFound(bare),
        )
        assertEquals(
            "No source found for tag 'x', nor for its nearest identifiable element, in this project.",
            SourceStatus.notFound(tagged),
        )
        assertEquals(SourceStatus.notFound(alone.query), SourceStatus.notFound(alone))
        // Nothing of its own, no relatives, and an activity that is not in this project.
        assertEquals(
            "No source found for this element in this project.",
            SourceStatus.notFound(SourceResult(SourceQuery(), null, emptyList())),
        )
    }

    @Test
    fun `nothing found says what was searched`() {
        val query = SourceQuery(testTag = "x", text = "y")

        assertNull(SourceStatus.found(SourceResult(query, null, emptyList())))
        assertEquals("No source found for tag 'x', text 'y' in this project.", SourceStatus.notFound(query))
    }

    @Test
    fun `a long value is shortened in the not-found message`() {
        val message = SourceStatus.notFound(SourceQuery(text = "a".repeat(100)))

        assertTrue(message.length < 100, message)
        assertTrue(message.contains("…"), message)
    }

    @Test
    fun `a candidate is listed as file, line and snippet`() {
        assertEquals(
            "Screen.kt:12 — Text(\"Hi\")",
            hit("Screen.kt", line = 12, snippet = "Text(\"Hi\")").presentation,
        )
        assertTrue(hit("Screen.kt", snippet = "x".repeat(200)).presentation.endsWith("…"))
    }

    private fun hit(
        fileName: String,
        offset: Int = 0,
        line: Int = 1,
        snippet: String = "",
        exactContext: Boolean = false,
        module: String? = null,
        packageName: String? = null,
        screenAffinity: Int = 0,
        pattern: String? = null,
    ) = SourceHit(
        url = "file:///project/$fileName",
        fileName = fileName,
        offset = offset,
        line = line,
        snippet = snippet,
        exactContext = exactContext,
        module = module,
        packageName = packageName,
        screenAffinity = screenAffinity,
        pattern = pattern,
    )

    private fun node(text: String = "", contentDescription: String = "", resourceId: String = "") = UiNode(
        className = "android.view.View",
        packageName = "p",
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = UiNode.Bounds(0, 0, 100, 100),
        clickable = true,
        longClickable = false,
        enabled = true,
        focused = false,
        focusable = true,
        scrollable = false,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        children = emptyList(),
    )
}
