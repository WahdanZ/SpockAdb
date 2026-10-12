package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.mcp.tools.StorageView
import spock.adb.storage.PrefItem
import spock.adb.storage.PrefValue
import spock.adb.storage.StorageFile
import spock.adb.storage.StorageKind

/** [StorageView] on its own: how a value is cut, measured and redacted. */
class StorageViewTest {

    private val file = StorageFile("shared_prefs/settings.xml", StorageKind.SHARED_PREFERENCES)

    private fun shown(key: String, value: String, maxValueChars: Int = 50): String =
        StorageView(maxValueChars = maxValueChars)
            .describe(file, listOf(PrefItem.Typed(key, PrefValue.StringValue(value))))
            .lines()
            .single { it.startsWith("$key ") }
            .substringAfter(" = ")

    @Test
    fun `the cut and the reported length are both in the value's own characters`() {
        // Every newline is two characters once escaped. Measuring the escaped text cut this after
        // 25 newlines while reporting the raw length, so neither number described the other.
        val value = "\n".repeat(60)

        val text = shown("note", value)

        assertEquals("\\n".repeat(50) + "…[truncated, 60 chars]", text)
    }

    @Test
    fun `asking for the reported length returns the whole value`() {
        val value = "\t".repeat(60)

        assertEquals("\\t".repeat(60), shown("note", value, maxValueChars = 60))
    }

    @Test
    fun `an escape is never split`() {
        // A control character with no short escape is six characters shown: \u0001.
        val value = "a".repeat(49) + "\u0001" + "b".repeat(20)

        val text = shown("note", value)

        assertEquals("a".repeat(49) + "\\u0001…[truncated, 70 chars]", text)
    }

    @Test
    fun `a cut never ends between the two halves of a surrogate pair`() {
        // The emoji is two UTF-16 characters and the 50th character is its first half.
        val value = "a".repeat(49) + "🚀" + "b".repeat(10)

        val text = shown("note", value)

        assertEquals("a".repeat(49) + "…[truncated, 61 chars]", text)
        assertTrue(text.none { it.isSurrogate() }, text)
    }

    @Test
    fun `a whole surrogate pair before the cut is kept`() {
        val value = "a".repeat(48) + "🚀" + "b".repeat(10)

        assertEquals("a".repeat(48) + "🚀…[truncated, 60 chars]", shown("note", value))
    }
}
