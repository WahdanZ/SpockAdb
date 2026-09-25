package spock.adb.uitree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Matching a rendered value — what the device shows — against the Kotlin template or `strings.xml`
 * format that produced it. Sources are written as they appear in a `.kt` file, quotes included.
 */
class SourceTemplateTest {

    private fun matches(source: String, value: String): Boolean =
        checkNotNull(SourceTemplate.pattern(source)) { "no pattern for $source" }.matches(value)

    // ---------------------------------------------------------------- templates

    @Test
    fun `a templated tag matches what it renders to, and only that`() {
        val source = "\"form_\${form}_button\""

        assertTrue(matches(source, "form_a_button"))
        assertTrue(matches(source, "form_checkout_button"))
        assertFalse(matches(source, "form__button"), "a hole stands for at least one character")
        assertFalse(matches(source, "form_a_buttons"), "anchored at the end")
        assertFalse(matches(source, "my_form_a_button"), "anchored at the start")
    }

    @Test
    fun `a short template takes the name, not what follows it`() {
        assertTrue(matches("\"carousel_\$row\"", "carousel_4"))
        assertFalse(matches("\"carousel_\$row\"", "carousel_"))
        assertTrue(matches("\"Size \$name.length\"", "Size bob.length"))
        assertFalse(matches("\"Size \$name.length\"", "Size 3"))
    }

    @Test
    fun `regex metacharacters in the fixed text are taken literally`() {
        val source = "\"Total (\${n}) [items].*+?\""

        assertTrue(matches(source, "Total (3) [items].*+?"))
        assertFalse(matches(source, "Total (3) [items]xx+?"))
        assertFalse(matches(source, "Total 3 items"))
    }

    @Test
    fun `an expression's braces nest, and strings inside it are skipped whole`() {
        assertTrue(matches("\"\${items.map { it.name }.joinToString()} selected\"", "a, b selected"))
        assertTrue(matches("\"\${if (on) \"}\" else \"{\"} done\"", "} done"))
        assertTrue(matches("\"\${label(\"\${n}\")}: end\"", "x: end"))
        assertTrue(matches("\"\${if (c == '}') 1 else 2} left\"", "1 left"))
    }

    @Test
    fun `escapes are decoded in a quoted string, including an escaped dollar`() {
        assertTrue(matches("\"cost \\\$\${n}\"", "cost \$5"))
        assertFalse(matches("\"cost \\\$\${n}\"", "cost 5"))
        assertTrue(matches("\"\\u00e9t\\u00e9 \$year\"", "été 2026"))
        assertTrue(matches("\"Say \\\"\$word\\\"\"", "Say \"hi\""))
    }

    @Test
    fun `a raw string has no escapes, and writes a dollar as a template of one`() {
        assertTrue(matches("\"\"\"a.b \$x\"\"\"", "a.b y"))
        assertFalse(matches("\"\"\"a.b \$x\"\"\"", "aXb y"))
        assertTrue(matches("\"\"\"C:\\n \$dir\"\"\"", "C:\\n tmp"))
        assertTrue(matches("\"\"\"\${'$'}\$amount due\"\"\"", "\$5 due"))
    }

    @Test
    fun `a template with no fixed letter or digit is too loose to be a pattern`() {
        assertNull(SourceTemplate.pattern("\"\$label \$it\""))
        assertNull(SourceTemplate.pattern("\"\${a}\${b}\""))
        assertNull(SourceTemplate.pattern("\"\$a: \$b\""))
    }

    @Test
    fun `a plain literal, a malformed template or no string at all is not a pattern`() {
        assertNull(SourceTemplate.pattern("\"form_a_button\""), "a plain literal is compared whole instead")
        assertNull(SourceTemplate.pattern("\"Costs \$ 5\""), "a lone dollar is text")
        assertNull(SourceTemplate.pattern("\"\${oops\""))
        assertNull(SourceTemplate.pattern("name"))
        assertNull(SourceTemplate.pattern("\""))
    }

    @Test
    fun `the Source line names a template without its quotes`() {
        assertEquals("form_\${form}_button", SourceTemplate.body("\"form_\${form}_button\""))
        assertEquals("raw \$x", SourceTemplate.body("\"\"\"raw \$x\"\"\""))
        assertEquals("Tapped %d", SourceTemplate.body("Tapped %d"))
    }

    // ---------------------------------------------------------------- strings.xml formats

    @Test
    fun `a strings xml value with format arguments matches what getString renders`() {
        assertTrue(SourceTemplate.formatPattern("Tapped %1\$d times")!!.matches("Tapped 3 times"))
        assertTrue(SourceTemplate.formatPattern("%.2f km")!!.matches("3.14 km"))
        assertTrue(SourceTemplate.formatPattern("Progress: %d%%")!!.matches("Progress: 40%"))
        assertTrue(SourceTemplate.formatPattern("%1\$s and %2\$s")!!.matches("salt and pepper"))
        assertFalse(SourceTemplate.formatPattern("Tapped %1\$d times")!!.matches("Tapped times"))
    }

    @Test
    fun `a value with no argument, or nothing fixed, is not a format pattern`() {
        assertNull(SourceTemplate.formatPattern("Plain text"))
        assertNull(SourceTemplate.formatPattern("100%%"))
        assertNull(SourceTemplate.formatPattern("%s"))
        assertNull(SourceTemplate.formatPattern("%d%%"))
    }

    // ---------------------------------------------------------------- index words

    @Test
    fun `several words are looked up, worded before numbers, longest first`() {
        assertEquals(listOf("nothing", "Last", "tap"), SourceTemplate.searchWords("Last tap: nothing yet"))
        assertEquals(listOf("Feed", "row", "1"), SourceTemplate.searchWords("Feed row 1"))
        assertEquals(listOf("row", "12345"), SourceTemplate.searchWords("row 12345 row"))
        assertEquals(listOf("form_a_button"), SourceTemplate.searchWords("form_a_button"))
        assertEquals(emptyList<String>(), SourceTemplate.searchWords("→ …"))
        assertEquals(SourceTemplate.MAX_SEARCH_WORDS, SourceTemplate.searchWords("one two three four five").size)
    }

    // ---------------------------------------------------------------- testTag calls

    @Test
    fun `a string passed to testTag is found after the name, by position or by name`() {
        assertEquals(1, SourceTemplate.tagArgumentOffset("(\"x\")"))
        assertEquals(7, SourceTemplate.tagArgumentOffset("(tag = \"x\")"))
        assertEquals(6, SourceTemplate.tagArgumentOffset("(\n    \"x\")"))
        assertEquals(1, SourceTemplate.tagArgumentOffset("(\"\"\"x\"\"\")"))
    }

    @Test
    fun `anything else passed to testTag, or its declaration, has no literal to read`() {
        assertNull(SourceTemplate.tagArgumentOffset("(tag)"))
        assertNull(SourceTemplate.tagArgumentOffset("(it.tag)"))
        assertNull(SourceTemplate.tagArgumentOffset("(tag: String) = this"))
        assertNull(SourceTemplate.tagArgumentOffset(" = \"x\""))
        assertNull(SourceTemplate.tagArgumentOffset(""))
    }
}
