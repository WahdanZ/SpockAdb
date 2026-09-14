package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SharedPrefsXmlTest {

    /** What `SharedPreferencesImpl` writes, character for character. */
    private val android = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="token">a &lt;b&gt; &amp; &quot;c&quot;&#10;line two</string>
            <boolean name="onboarding_seen" value="false" />
            <int name="launches" value="-3" />
            <long name="last_synced_at" value="1757750400000" />
            <float name="scale" value="1.5" />
            <set name="tags">
                <string>a</string>
                <string>b</string>
            </set>
            <set name="empty_set" />
            <string name="empty"></string>
        </map>

    """.trimIndent()

    @Test
    fun `reads every type SharedPreferences writes`() {
        assertEquals(
            listOf(
                PrefItem.Typed("token", PrefValue.StringValue("a <b> & \"c\"\nline two")),
                PrefItem.Typed("onboarding_seen", PrefValue.BooleanValue(false)),
                PrefItem.Typed("launches", PrefValue.IntValue(-3)),
                PrefItem.Typed("last_synced_at", PrefValue.LongValue(1_757_750_400_000)),
                PrefItem.Typed("scale", PrefValue.FloatValue(1.5f)),
                PrefItem.Typed("tags", PrefValue.StringSetValue(listOf("a", "b"))),
                PrefItem.Typed("empty_set", PrefValue.StringSetValue(emptyList())),
                PrefItem.Typed("empty", PrefValue.StringValue("")),
            ),
            SharedPrefsXml.read(android.toByteArray()),
        )
    }

    @Test
    fun `an unchanged file is written back byte for byte`() {
        assertEquals(android, String(SharedPrefsXml.write(android.toByteArray(), emptyList())))
    }

    @Test
    fun `an edit changes only its own line and keeps its position`() {
        val written = String(
            SharedPrefsXml.write(
                android.toByteArray(),
                listOf(PrefChange.Put("onboarding_seen", PrefValue.BooleanValue(true))),
            ),
        )

        val expected = android.replace("\"onboarding_seen\" value=\"false\"", "\"onboarding_seen\" value=\"true\"")
        assertEquals(expected, written)
    }

    @Test
    fun `adds a new key at the end and removes one in place`() {
        val written = SharedPrefsXml.write(
            android.toByteArray(),
            listOf(
                PrefChange.Remove("token"),
                PrefChange.Put("new_flag", PrefValue.BooleanValue(true)),
            ),
        )
        val keys = SharedPrefsXml.read(written).map { it.key }

        assertEquals(
            listOf("onboarding_seen", "launches", "last_synced_at", "scale", "tags", "empty_set", "empty", "new_flag"),
            keys,
        )
    }

    @Test
    fun `a type change replaces the element`() {
        val written = SharedPrefsXml.write(
            android.toByteArray(),
            listOf(PrefChange.Put("launches", PrefValue.LongValue(7))),
        )

        assertTrue(String(written).contains("<long name=\"launches\" value=\"7\" />"), String(written))
        assertFalse(String(written).contains("<int name=\"launches\""))
    }

    @Test
    fun `elements the editor does not model survive an edit elsewhere`() {
        val file = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <null name="cleared" />
                <int-array name="ids" num="2">
                    <item value="1" />
                    <item value="2" />
                </int-array>
                <boolean name="flag" value="true" />
                <boolean name="odd" value="yes" />
            </map>

        """.trimIndent()

        val changes = listOf(PrefChange.Put("flag", PrefValue.BooleanValue(false)))
        val written = String(SharedPrefsXml.write(file.toByteArray(), changes))

        assertEquals(file.replace("\"flag\" value=\"true\"", "\"flag\" value=\"false\""), written)
        assertEquals(
            listOf(
                PrefItem.Opaque("cleared", "<null>"),
                PrefItem.Opaque("ids", "<int-array>"),
                PrefItem.Typed("flag", PrefValue.BooleanValue(false)),
                PrefItem.Opaque("odd", "<boolean>"),
            ),
            SharedPrefsXml.read(written.toByteArray()),
        )
    }

    @Test
    fun `an opaque entry cannot be overwritten or removed`() {
        val file = "<map><null name=\"cleared\" /></map>".toByteArray()

        assertThrows<IllegalArgumentException> {
            SharedPrefsXml.write(file, listOf(PrefChange.Put("cleared", PrefValue.StringValue("x"))))
        }
        assertThrows<IllegalArgumentException> { SharedPrefsXml.write(file, listOf(PrefChange.Remove("cleared"))) }
    }

    @Test
    fun `refuses a type SharedPreferences cannot store`() {
        val thrown = assertThrows<IllegalArgumentException> {
            SharedPrefsXml.write(android.toByteArray(), listOf(PrefChange.Put("d", PrefValue.DoubleValue(1.0))))
        }
        assertTrue(thrown.message!!.contains("double"), thrown.message)
    }

    @Test
    fun `removing a key that is not there is an error, not a silent no-op`() {
        assertThrows<IllegalArgumentException> {
            SharedPrefsXml.write(android.toByteArray(), listOf(PrefChange.Remove("missing")))
        }
    }

    @Test
    fun `control characters are escaped the way Android escapes them`() {
        val entry = PrefChange.Put("k\"", PrefValue.StringValue("a\tb\r\n"))
        val written = String(SharedPrefsXml.write("<map />".toByteArray(), listOf(entry)))

        assertTrue(written.contains("<string name=\"k&quot;\">a&#9;b&#13;&#10;</string>"), written)
        assertEquals(listOf(PrefItem.Typed(entry.key, entry.value)), SharedPrefsXml.read(written.toByteArray()))
    }

    @Test
    fun `an empty map is written the way Android writes it`() {
        assertEquals(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n",
            String(SharedPrefsXml.write("<map><int name=\"a\" value=\"1\" /></map>".toByteArray(), listOf(REMOVE_A))),
        )
    }

    @Test
    fun `non-ASCII survives a round trip`() {
        val change = PrefChange.Put("name", PrefValue.StringValue("أحمد 😀"))
        val written = SharedPrefsXml.write("<map />".toByteArray(), listOf(change))

        assertEquals(listOf(PrefItem.Typed("name", PrefValue.StringValue("أحمد 😀"))), SharedPrefsXml.read(written))
    }

    @Test
    fun `malformed or foreign files are refused rather than partly read`() {
        listOf(
            "",
            "<map><string name=\"a\">",
            "<resources><string name=\"a\">x</string></resources>",
            "not xml at all",
        ).forEach { file ->
            assertThrows<PrefsFormatException>(file) { SharedPrefsXml.read(file.toByteArray()) }
        }
    }

    @Test
    fun `a DOCTYPE is refused, so no entity from the device is ever expanded`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE map [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <map><string name="a">&secret;</string></map>
        """.trimIndent()

        assertThrows<PrefsFormatException> { SharedPrefsXml.read(xxe.toByteArray()) }
    }

    @Test
    fun `encrypted preferences are recognised by their keyset entries`() {
        val encrypted = """
            <map>
                <string name="__androidx_security_crypto_encrypted_prefs_key_keyset__">12a9…</string>
                <string name="__androidx_security_crypto_encrypted_prefs_value_keyset__">1288…</string>
                <string name="ASjk2…">AUZ9…</string>
            </map>
        """.trimIndent()

        assertTrue(SharedPrefsXml.read(encrypted.toByteArray()).isEncryptedPreferences())
        assertFalse(SharedPrefsXml.read(android.toByteArray()).isEncryptedPreferences())
    }

    private companion object {
        val REMOVE_A = PrefChange.Remove("a")
    }
}
