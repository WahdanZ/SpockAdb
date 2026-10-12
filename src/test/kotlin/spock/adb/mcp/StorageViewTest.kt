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

    private fun shownValue(key: String, value: PrefValue): String =
        StorageView()
            .describe(file, listOf(PrefItem.Typed(key, value)))
            .lines()
            .single { it.startsWith("$key ") }
            .substringAfter(" = ")

    @Test
    fun `each secret-looking key name is redacted`() {
        listOf(
            "auth_token", "refreshToken", "client_secret", "user_password", "passwd", "db_pwd", "session_id",
            "api_key", "apiKey", "jwt", "bearer", "cookie_jar", "credentials", "private_key", "privateKey",
        ).forEach { key ->
            assertEquals("<redacted, 6 chars>", shownValue(key, PrefValue.StringValue("abc123")), key)
        }
    }

    @Test
    fun `author and authority are not auth`() {
        assertEquals("Spock", shownValue("author", PrefValue.StringValue("Spock")))
        assertEquals("sample.spock.adb", shownValue("content_authority", PrefValue.StringValue("sample.spock.adb")))
    }

    @Test
    fun `a JWT under a harmless key is redacted`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzcG9jayJ9.c2lnbmF0dXJl"

        assertEquals(
            "<redacted: looks like a JWT, ${jwt.length} chars>",
            shownValue("profile", PrefValue.StringValue(jwt)),
        )
        assertEquals(
            "<redacted: looks like a JWT, ${jwt.length + 7} chars>",
            shownValue("header", PrefValue.StringValue("Bearer $jwt")),
        )
    }

    @Test
    fun `JSON holding a secret-looking key at any depth is redacted whole, naming the key`() {
        val user = """{"name":"Spock","profile":{"links":[{"rel":"self"},{"accessToken":"abc"}]}}"""

        assertEquals(
            "<redacted: holds accessToken, ${user.length} chars>",
            shownValue("flutter.user", PrefValue.StringValue(user)),
        )
    }

    @Test
    fun `JSON without one is shown, and so is text that only looks like JSON`() {
        val feed = """{"items":[{"id":1,"title":"Item 1","author":"Spock"}]}"""

        assertEquals(feed, shownValue("cached_feed", PrefValue.StringValue(feed)))
        assertEquals("{not json", shownValue("note", PrefValue.StringValue("{not json")))
    }

    @Test
    fun `booleans, ints and longs are shown under any key`() {
        assertEquals("true", shownValue("session_active", PrefValue.BooleanValue(true)))
        assertEquals("3", shownValue("session_count", PrefValue.IntValue(3)))
        assertEquals("1700000000000", shownValue("token_expiry_ms", PrefValue.LongValue(1_700_000_000_000L)))
    }

    @Test
    fun `a string set holding a JWT is redacted`() {
        val value = PrefValue.StringSetValue(listOf("plain", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig"))

        assertTrue(shownValue("recent", value).startsWith("<redacted: looks like a JWT"))
    }

    @Test
    fun `the note says the redaction is best-effort, by key name and value shape`() {
        val text = StorageView().describe(file, listOf(PrefItem.Typed("profile", PrefValue.StringValue("eyJa.eyJb.c"))))

        assertTrue(text.contains("1 value is redacted because the key or value looks secret (best-effort"), text)
    }

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
