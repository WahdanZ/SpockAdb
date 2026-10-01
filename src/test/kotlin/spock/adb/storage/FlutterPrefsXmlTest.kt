package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64

class FlutterPrefsXmlTest {

    private companion object {
        /** The plugin's list marker: base64 of "This is the prefix for a list." */
        const val LIST_MARKER = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    }

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

    /** The raw string the file holds under `flutter.recent_searches`, before any decoding. */
    private fun storedList(xml: String) =
        (SharedPrefsXml.read(xml.toByteArray()).first { it.key == "flutter.recent_searches" } as PrefItem.Typed)
            .value.text()

    /**
     * The same serialized bytes and base64 the plugin produced, wrapped as Android's
     * `Base64.DEFAULT` wraps it: 76-character lines, each ending in `\n`. The device's value also
     * ends in the indentation Android's XML writer puts before the closing tag after a newline,
     * which is layout, not part of what the plugin stored.
     */
    @Test
    fun `a list is written in the exact form the plugin writes`() {
        val list = PrefValue.StringListValue(listOf("adb", "flutter", "spock"))
        val other = String(
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("flutter.recent_searches", PrefValue.StringListValue(listOf("xyz")))),
            )
        )
        assertTrue(storedList(other) != storedList(device), "the edit below must be a real change")

        val edited = String(
            FlutterPrefsXml.write(other.toByteArray(), listOf(PrefChange.Put("flutter.recent_searches", list)))
        )

        val written = storedList(edited)
        assertEquals(storedList(device).trimEnd(' '), written)
        val lines = written.removePrefix(LIST_MARKER).split("\n")
        assertEquals(listOf(76, 36, 0), lines.map { it.length }, written)
        assertEquals(list, valueOf(edited, "flutter.recent_searches"))
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
    fun `a native int key stays an int`() {
        val native = device.replace("</map>", "    <int name=\"launches\" value=\"1\" />\n</map>")
        val edited = String(
            FlutterPrefsXml.write(native.toByteArray(), listOf(PrefChange.Put("launches", PrefValue.IntValue(3))))
        )
        assertTrue(edited.contains("<int name=\"launches\" value=\"3\" />"), edited)
    }

    @Test
    fun `native keys take the types SharedPreferences has, and not Dart's`() {
        val float = String(
            FlutterPrefsXml.write(device.toByteArray(), listOf(PrefChange.Put("scale", PrefValue.FloatValue(1.5f))))
        )
        assertTrue(float.contains("<float name=\"scale\" value=\"1.5\" />"), float)
        val set = String(
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("tags", PrefValue.StringSetValue(listOf("a"))))
            )
        )
        assertEquals(PrefValue.StringSetValue(listOf("a")), valueOf(set, "tags"))

        val double = assertThrows<IllegalArgumentException> {
            FlutterPrefsXml.write(device.toByteArray(), listOf(PrefChange.Put("ratio", PrefValue.DoubleValue(0.5))))
        }
        assertTrue(double.message!!.contains("flutter."), double.message)
        assertThrows<IllegalArgumentException> {
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("tags", PrefValue.StringListValue(listOf("a"))))
            )
        }
    }

    @Test
    fun `a flutter key refuses a string set`() {
        assertThrows<IllegalArgumentException> {
            FlutterPrefsXml.write(
                device.toByteArray(),
                listOf(PrefChange.Put("flutter.tags", PrefValue.StringSetValue(listOf("a"))))
            )
        }
    }

    @Test
    fun `offers every type a key in this file can hold`() {
        assertEquals(
            SharedPrefsXml.types + PrefType.DOUBLE + PrefType.STRING_LIST,
            FlutterPrefsXml.types,
        )
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
            FlutterPrefsXml.write(
                crafted.toByteArray(),
                listOf(PrefChange.Put("flutter.recent_searches", PrefValue.StringListValue(listOf("a")))),
            )
        }
    }

    @Test
    fun `a value that cannot be decoded can still be deleted`() {
        val bigInteger = device.replace(
            Regex("""(<string name="flutter.recent_searches">)[^<]*(</string>)"""),
            "$1VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy1234$2",
        )
        assertTrue(read(bigInteger)["flutter.recent_searches"] is PrefItem.Opaque)

        val edited = String(
            FlutterPrefsXml.write(bigInteger.toByteArray(), listOf(PrefChange.Remove("flutter.recent_searches")))
        )

        assertTrue(read(edited)["flutter.recent_searches"] == null, edited)
    }

    /** [device] with `flutter.recent_searches` holding [encoded] behind the list marker. */
    private fun withList(encoded: String) = device.replace(
        Regex("""(<string name="flutter.recent_searches">)[^<]*(</string>)"""),
        "$1$LIST_MARKER$encoded$2",
    )

    private fun serialized(value: Any): ByteArray = ByteArrayOutputStream().also { out ->
        ObjectOutputStream(out).use { it.writeObject(value) }
    }.toByteArray()

    private fun listItem(encoded: String) = read(withList(encoded))["flutter.recent_searches"]

    private fun assertOpaque(encoded: String) {
        val item = listItem(encoded)
        assertTrue(item is PrefItem.Opaque, "$item")
    }

    /**
     * The device's three-element list with its `size` field rewritten: a few dozen bytes that
     * declare a list of [size] elements, which `ArrayList.readObject` allocates up front.
     */
    private fun declaringSize(size: Int): String {
        val bytes = serialized(arrayListOf("adb", "flutter", "spock"))
        // `xp` ends the class descriptor; the int after it is `size`, then the block holding capacity.
        val head = byteArrayOf(0x78, 0x70, 0, 0, 0, 3, 0x77, 0x04)
        val at = (0..bytes.size - head.size).first { i -> head.indices.all { bytes[i + it] == head[it] } }
        ByteBuffer.wrap(bytes).putInt(at + 2, size)
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `a list declaring a huge size is not allocated`() {
        assertTimeout(Duration.ofSeconds(5)) {
            assertOpaque(declaringSize(400_000_000))
            assertOpaque(declaringSize(Int.MAX_VALUE - 8))
        }
    }

    @Test
    fun `a long list of strings still decodes`() {
        val many = (1..5_000).map { "item $it" }
        assertEquals(
            PrefValue.StringListValue(many),
            (listItem(Base64.getEncoder().encodeToString(serialized(ArrayList(many)))) as PrefItem.Typed).value,
        )
    }

    @Test
    fun `a list nested in a list is not decoded`() {
        assertOpaque(Base64.getEncoder().encodeToString(serialized(arrayListOf(arrayListOf("a")))))
        assertOpaque(Base64.getEncoder().encodeToString(serialized(arrayListOf(arrayOf<Any>("a")))))
    }

    @Test
    fun `a list holding something other than a string is not decoded`() {
        assertOpaque(Base64.getEncoder().encodeToString(serialized(arrayListOf<Any>("a", 1))))
    }

    @Test
    fun `an array that is not an ArrayList is not decoded`() {
        assertOpaque(Base64.getEncoder().encodeToString(serialized(arrayOf<Any>("a", "b"))))
    }

    @Test
    fun `a truncated stream is not decoded`() {
        val bytes = serialized(arrayListOf("adb", "flutter", "spock"))
        assertOpaque(Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 6)))
    }

    @Test
    fun `base64 with anything but line breaks in it is not decoded`() {
        val valid = Base64.getEncoder().encodeToString(serialized(arrayListOf("adb")))
        // A lenient decoder skips the `*` and decodes the rest as if it were not there.
        assertOpaque(valid.substring(0, 8) + "*" + valid.substring(8))
        assertOpaque("not base64!")
    }

    @Test
    fun `a JSON list that is not an array of strings is not decoded`() {
        assertOpaque("![&quot;a&quot;,1]")
        assertOpaque("!{}")
        assertOpaque("![")
    }

    @Test
    fun `only FlutterSharedPreferences xml uses this format`() {
        assertEquals(FlutterPrefsXml, AppStoragePaths.parse("shared_prefs/FlutterSharedPreferences.xml").format)
        assertEquals(SharedPrefsXml, AppStoragePaths.parse("shared_prefs/settings.xml").format)
    }
}
