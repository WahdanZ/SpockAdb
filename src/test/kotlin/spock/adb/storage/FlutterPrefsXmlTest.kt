package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FlutterPrefsXmlTest {

    /** Written by shared_preferences 2.3 on Android, from `sample/flutter_app`'s Storage screen. */
    private val device = """
        <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="flutter.recent_searches">VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3QurO0ABXNyABNqYXZhLnV0aWwuQXJyYXlMaXN0eIHSHZnHYZ0DAAFJAARzaXpleHAAAAADdwQAAAAD&#10;dAADYWRidAAHZmx1dHRlcnQABXNwb2NreA==&#10;    </string>
            <string name="flutter.volume">VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu0.75</string>
            <string name="flutter.username">spock</string>
            <long name="flutter.launch_count" value="1" />
            <boolean name="flutter.dark_mode" value="true" />
        </map>

    """.trimIndent()

    private fun read(xml: String) = FlutterPrefsXml.read(xml.toByteArray()).associateBy { it.key }

    private fun valueOf(xml: String, key: String) = (read(xml)[key] as PrefItem.Typed).value

    @Test
    fun `reads the plugin's encoded values as their Dart types`() {
        val items = read(device)
        assertEquals(
            PrefValue.StringListValue(listOf("adb", "flutter", "spock")),
            (items["flutter.recent_searches"] as PrefItem.Typed).value
        )
        assertEquals(PrefValue.DoubleValue(0.75), (items["flutter.volume"] as PrefItem.Typed).value)
        assertEquals(PrefValue.StringValue("spock"), (items["flutter.username"] as PrefItem.Typed).value)
        assertEquals(PrefValue.LongValue(1), (items["flutter.launch_count"] as PrefItem.Typed).value)
        assertEquals(PrefValue.BooleanValue(true), (items["flutter.dark_mode"] as PrefItem.Typed).value)
    }

    @Test
    fun `an unrelated edit leaves the encoded values byte for byte`() {
        val written = String(
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("flutter.dark_mode", PrefValue.BooleanValue(true)))
            )
        )
        assertEquals(device, written)
    }

    /**
     * The same serialized bytes and base64 the plugin produced. Whitespace is not compared:
     * Android's XML writer indents the closing tag after a value ending in a newline, and both
     * Base64 decoders skip it.
     */
    @Test
    fun `a list is written in the exact form the plugin writes`() {
        val edited = FlutterPrefsXml.write(
            device.replace("adb", "xyz").toByteArray(),
            listOf(
                PrefChange.Put("flutter.recent_searches", PrefValue.StringListValue(listOf("adb", "flutter", "spock")))
            ),
        )
        fun stored(xml: String) = (
            SharedPrefsXml.read(
            xml.toByteArray()
        ).first { it.key == "flutter.recent_searches" } as PrefItem.Typed
        )
            .value.text().filterNot(Char::isWhitespace)
        assertEquals(stored(device), stored(String(edited)))
    }

    @Test
    fun `a list may repeat values and keeps their order`() {
        val list = PrefValue.StringListValue(listOf("b", "a", "b"))
        val edited = String(
            FlutterPrefsXml.write(device.toByteArray(), listOf(PrefChange.Put("flutter.recent_searches", list)))
        )
        assertEquals(list, valueOf(edited, "flutter.recent_searches"))
    }

    @Test
    fun `a list stored as JSON stays JSON`() {
        val json = device.replace(
            Regex("""(<string name="flutter.recent_searches">)[^<]*(</string>)"""),
            "$1VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![&quot;a&quot;]$2",
        )
        assertEquals(PrefValue.StringListValue(listOf("a")), valueOf(json, "flutter.recent_searches"))
        val edited = String(
            FlutterPrefsXml.write(
                json.toByteArray(),
                listOf(PrefChange.Put("flutter.recent_searches", PrefValue.StringListValue(listOf("a", "b"))))
            )
        )
        assertTrue(edited.contains("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![&quot;a&quot;,&quot;b&quot;]"), edited)
    }

    @Test
    fun `a double is written behind the plugin's marker`() {
        val edited = String(
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("flutter.volume", PrefValue.DoubleValue(0.5)))
            )
        )
        assertTrue(
            edited.contains("<string name=\"flutter.volume\">VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu0.5</string>"),
            edited
        )
    }

    @Test
    fun `an int is stored as a long, as Dart reads it`() {
        val edited = String(
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("flutter.launch_count", PrefValue.IntValue(5)))
            )
        )
        assertTrue(edited.contains("<long name=\"flutter.launch_count\" value=\"5\" />"), edited)
    }

    @Test
    fun `refuses a string that Flutter would read back as another type`() {
        val error = assertThrows<IllegalArgumentException> {
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(
                    PrefChange.Put(
                        "flutter.username",
                        PrefValue.StringValue("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu1")
                    )
                )
            )
        }
        assertTrue(error.message!!.contains("type marker"))
    }

    @Test
    fun `refuses types Dart cannot hold`() {
        assertThrows<IllegalArgumentException> {
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("flutter.scale", PrefValue.FloatValue(1f)))
            )
        }
    }

    @Test
    fun `native keys are passed through without decoding`() {
        val native = device.replace("flutter.volume", "volume")
        assertEquals(PrefValue.StringValue("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu0.75"), valueOf(native, "volume"))
    }

    @Test
    fun `a list that is not a plain ArrayList of strings is shown but not decoded`() {
        // A serialized java.util.Date behind the list marker: the filter must refuse to build it.
        val date = java.io.ByteArrayOutputStream().also {
            java.io.ObjectOutputStream(
            it
        ).use { s -> s.writeObject(java.util.Date(0)) }
        }
        val crafted = device.replace(
            Regex("""(<string name="flutter.recent_searches">)[^<]*(</string>)"""),
            "$1VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu${java.util.Base64.getEncoder().encodeToString(
                date.toByteArray()
            )}$2",
        )
        val item = read(crafted)["flutter.recent_searches"]
        assertTrue(item is PrefItem.Opaque, "$item")
        assertThrows<IllegalArgumentException> {
            FlutterPrefsXml.write(crafted.toByteArray(), listOf(PrefChange.Remove("flutter.recent_searches")))
        }
    }

    @Test
    fun `only FlutterSharedPreferences xml uses this format`() {
        assertEquals(FlutterPrefsXml, AppStoragePaths.parse("shared_prefs/FlutterSharedPreferences.xml").format)
        assertEquals(SharedPrefsXml, AppStoragePaths.parse("shared_prefs/settings.xml").format)
    }
}
