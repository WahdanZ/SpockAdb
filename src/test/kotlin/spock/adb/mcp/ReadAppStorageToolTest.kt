package spock.adb.mcp

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import spock.adb.command.FakeStorageDevice
import spock.adb.mcp.tools.ConfirmationWording
import spock.adb.mcp.tools.ReadAppStorageTool

/**
 * `android_read_app_storage` keeps its answer bounded and its secrets back (#182): one cached JSON
 * value made a Flutter prefs file 40 116 characters, and the same files hold auth tokens.
 */
class ReadAppStorageToolTest {

    private val bigJson = """{"items":[""" + (1..1_000).joinToString(",") { """{"id":$it,"title":"Item $it"}""" } + "]}"

    private val storage = FakeStorageDevice().apply {
        files[PREFS] = """
            <map>
                <string name="cached_feed">$bigJson</string>
                <string name="auth_token">eyJhbGciOiJIUzI1NiJ9.fake.signature</string>
                <string name="refreshToken">rt-0123456789abcdef</string>
                <string name="user_password">hunter2</string>
                <boolean name="session_active" value="true" />
                <string name="note">line one&#10;line two</string>
                <int name="launch_count" value="3" />
            </map>
        """.trimIndent().toByteArray()
    }

    private fun context(approve: Boolean = false) = FakeToolContext(
        available = listOf(FakeToolContext.device("emulator-5554").copy(device = storage.device)),
        confirmationAnswer = approve,
    )

    private fun read(context: FakeToolContext = context(), extra: JsonObject.() -> Unit = {}) =
        ReadAppStorageTool().execute(JsonObject().apply { addProperty("file", PREFS) }.apply(extra), context)

    private fun line(text: String, key: String) = text.lines().single { it.startsWith("$key ") }

    @Test
    fun `a long value is cut at 500 characters, with its full length`() {
        val text = read().text()

        val feed = line(text, "cached_feed")
        assertTrue(feed.endsWith("…[truncated, ${bigJson.length} chars]"), feed)
        val marker = "…[truncated, ${bigJson.length} chars]"
        assertEquals("cached_feed (string) = ".length + 500 + marker.length, feed.length)
        assertTrue(text.contains("1 value is cut at 500 characters"), text)
        assertTrue(text.length < 5_000, "the whole answer is ${text.length} chars")
    }

    @Test
    fun `maxValueChars raises the cut, within its range`() {
        val text = read { addProperty("maxValueChars", 2_000) }.text()

        assertTrue(line(text, "cached_feed").contains("…[truncated, ${bigJson.length} chars]"))
        assertTrue(line(text, "cached_feed").length > 2_000)
    }

    @Test
    fun `a maxValueChars outside its range is refused before the device is read`() {
        listOf(49, 0, -5, 40_001).forEach { wanted ->
            storage.commands.clear()

            val result = read { addProperty("maxValueChars", wanted) }

            assertTrue(result.isError, "$wanted should be refused")
            assertEquals("maxValueChars is 50-40000, not $wanted.", result.text())
            assertTrue(storage.commands.none { it.contains("run-as") }, "nothing read: ${storage.commands}")
        }
    }

    @Test
    fun `key reads only the matching entries, ignoring case`() {
        val text = read { addProperty("key", "LAUNCH") }.text()

        assertEquals(
            "$PREFS (SharedPreferences, 7 entries)\n" +
                "Showing 1 entry whose key contains 'LAUNCH'.\n" +
                "launch_count (int) = 3",
            text,
        )
    }

    @Test
    fun `a key that matches nothing says so`() {
        val text = read { addProperty("key", "nothing_like_this") }.text()

        assertTrue(text.contains("No entry has a key containing 'nothing_like_this'."), text)
    }

    @Test
    fun `secret-looking values are redacted without asking`() {
        val context = context()

        val text = read(context).text()

        assertEquals("auth_token (string) = <redacted, 35 chars>", line(text, "auth_token"))
        assertEquals("refreshToken (string) = <redacted, 19 chars>", line(text, "refreshToken"))
        assertEquals("user_password (string) = <redacted, 7 chars>", line(text, "user_password"))
        assertFalse(text.contains("hunter2"), text)
        assertTrue(text.contains("3 values are redacted"), text)
        assertTrue(text.contains("showSecrets: true"), text)
        assertTrue(context.confirmations.isEmpty(), "reading never asks")
    }

    @Test
    fun `a boolean is shown even under a secret-looking key`() {
        assertEquals("session_active (boolean) = true", line(read().text(), "session_active"))
    }

    @Test
    fun `showSecrets asks the developer, naming the keys, and shows them when allowed`() {
        val context = context(approve = true)

        val text = read(context) { addProperty("showSecrets", true) }.text()

        assertEquals(listOf("android_read_app_storage"), context.confirmations)
        // Not the destructive wording: nothing on the device changes, so nothing "cannot be undone".
        assertEquals(ConfirmationWording.REVEAL_SECRETS, context.confirmationWordings.single())
        assertEquals("AI Agent Asks to See Secret Values", context.confirmationWordings.single().title)
        assertEquals(null, context.confirmationWordings.single().closingLine)
        val summary = context.confirmationSummaries.single()
        assertTrue(summary.contains("3 value(s)") && summary.contains("'auth_token'"), summary)
        assertFalse(summary.contains("hunter2"), "the dialog names keys, not values: $summary")
        assertEquals("user_password (string) = hunter2", line(text, "user_password"))
        assertTrue(text.contains("the developer allowed it"), text)
    }

    @Test
    fun `a declined reveal returns the redacted answer, not an error`() {
        val context = context(approve = false)

        val result = read(context) { addProperty("showSecrets", true) }

        assertFalse(result.isError, result.text())
        assertEquals(listOf("android_read_app_storage"), context.confirmations)
        assertFalse(result.text().contains("hunter2"), result.text())
        assertTrue(result.text().contains("the developer declined to reveal them"), result.text())
    }

    @Test
    fun `showSecrets does not ask when nothing shown is redacted`() {
        val context = context(approve = true)

        read(context) {
            addProperty("showSecrets", true)
            addProperty("key", "launch")
        }

        assertTrue(context.confirmations.isEmpty(), "nothing to reveal, so nothing to ask")
    }

    @Test
    fun `a value with newlines stays on its own line`() {
        assertEquals("note (string) = line one\\nline two", line(read().text(), "note"))
    }

    private companion object {
        const val PREFS = "shared_prefs/settings.xml"
    }
}
