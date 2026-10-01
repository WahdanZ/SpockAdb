package spock.adb.storage

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.ObjectInputFilter
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.Base64

/**
 * `shared_prefs/FlutterSharedPreferences.xml`, as Flutter's shared_preferences plugin writes it.
 *
 * It is an ordinary SharedPreferences file, so [SharedPrefsXml] reads and writes it. What this
 * adds is the plugin's encoding of the `flutter.`-prefixed keys: Dart has no float, set or 32-bit
 * int, so the plugin stores ints as `<long>`, and doubles and lists as a `<string>` behind a fixed
 * marker:
 *
 * ```
 * <string name="flutter.volume">VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu0.75</string>
 * <string name="flutter.tags">VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3QurO0AB…</string>
 * <string name="flutter.tags">VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!["a","b"]</string>
 * ```
 *
 * The first list is a base64 Java-serialized `ArrayList`; newer plugins write the JSON one.
 *
 * Those read here as a double and a string list, and an edit writes them back in the same
 * encoding — a list keeps the form it already had, and a new one takes the Java-serialized form,
 * which every plugin version reads. Keys without the `flutter.` prefix belong to the app's native
 * code and are passed through untouched.
 */
internal object FlutterPrefsXml : PrefsFormat {

    const val FILE_NAME = "FlutterSharedPreferences.xml"

    /** Every key the Dart side reads or writes starts with this. */
    const val KEY_PREFIX = "flutter."

    private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    private const val JSON_LIST_PREFIX = "$LIST_PREFIX!"
    private const val DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    private const val BIG_INTEGER_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy"
    private val MARKERS = listOf(LIST_PREFIX, DOUBLE_PREFIX, BIG_INTEGER_PREFIX)

    /** Android's `Base64.DEFAULT`, which the plugin uses: 76-character lines, each ending in `\n`. */
    private const val BASE64_LINE = 76

    /**
     * What any key in this file can hold. Which of them a given key takes is decided per key in
     * [encoded]: a `flutter.` key holds what Dart can — no float or set, and an int is written as
     * the long Dart reads — and a native key holds what SharedPreferences can, never a double or
     * a string list.
     */
    override val types = SharedPrefsXml.types + PrefType.DOUBLE + PrefType.STRING_LIST

    /** What a `flutter.` key can hold; an int is accepted and written as a long. */
    private val DART_TYPES = listOf(
        PrefType.BOOLEAN,
        PrefType.INT,
        PrefType.LONG,
        PrefType.DOUBLE,
        PrefType.STRING,
        PrefType.STRING_LIST,
    )

    override fun read(bytes: ByteArray): List<PrefItem> = SharedPrefsXml.read(bytes).map(::decoded)

    override fun write(original: ByteArray, changes: List<PrefChange>): ByteArray {
        val stored = SharedPrefsXml.read(original).associateBy { it.key }
        val decoded = stored.mapValues { (_, item) -> decoded(item) }
        val encoded = changes.map { change ->
            require(decoded[change.key] !is PrefItem.Opaque || stored[change.key] is PrefItem.Opaque) {
                "'${change.key}' holds a value this editor cannot read, so it is left as it is."
            }
            when (change) {
                is PrefChange.Remove -> change
                is PrefChange.Put -> PrefChange.Put(change.key, encoded(change.key, change.value, stored[change.key]))
            }
        }
        return SharedPrefsXml.write(original, encoded)
    }

    private fun decoded(item: PrefItem): PrefItem {
        val text = ((item as? PrefItem.Typed)?.value as? PrefValue.StringValue)?.value
        if (text == null || !item.key.startsWith(KEY_PREFIX)) return item
        return when {
            text.startsWith(JSON_LIST_PREFIX) -> jsonList(text.removePrefix(JSON_LIST_PREFIX))
                ?.let { PrefItem.Typed(item.key, PrefValue.StringListValue(it)) }
                ?: PrefItem.Opaque(item.key, "Flutter list that could not be decoded")
            text.startsWith(LIST_PREFIX) -> serializedList(text.removePrefix(LIST_PREFIX))
                ?.let { PrefItem.Typed(item.key, PrefValue.StringListValue(it)) }
                ?: PrefItem.Opaque(item.key, "Flutter list that could not be decoded")
            text.startsWith(DOUBLE_PREFIX) -> text.removePrefix(DOUBLE_PREFIX).toDoubleOrNull()
                ?.let { PrefItem.Typed(item.key, PrefValue.DoubleValue(it)) }
                ?: PrefItem.Opaque(item.key, "Flutter double that could not be decoded")
            text.startsWith(BIG_INTEGER_PREFIX) -> PrefItem.Opaque(item.key, "Flutter BigInteger")
            else -> item
        }
    }

    /**
     * [value] as the plugin stores it under [key]. A native key's value is written as it is, and
     * [SharedPrefsXml] checks it as it would in any other file.
     */
    private fun encoded(key: String, value: PrefValue, stored: PrefItem?): PrefValue {
        if (!key.startsWith(KEY_PREFIX)) {
            require(value !is PrefValue.DoubleValue && value !is PrefValue.StringListValue) {
                "Only flutter. keys hold a ${value.type.label} in this file; '$key' belongs to the app's " +
                    "native code, which can store: ${SharedPrefsXml.types.joinToString()}."
            }
            return value
        }
        return when (value) {
            is PrefValue.IntValue -> PrefValue.LongValue(value.value.toLong())
            is PrefValue.DoubleValue -> PrefValue.StringValue(DOUBLE_PREFIX + value.value)
            is PrefValue.StringListValue -> {
                val wasJson = ((stored as? PrefItem.Typed)?.value as? PrefValue.StringValue)
                    ?.value?.startsWith(JSON_LIST_PREFIX) == true
                PrefValue.StringValue(if (wasJson) jsonEncoded(value.values) else serializedEncoded(value.values))
            }
            is PrefValue.StringValue -> {
                // Flutter would read such a string back as a double or a list, not as the text.
                require(MARKERS.none { value.value.startsWith(it) }) {
                    "A string under a flutter. key cannot start with the plugin's type marker."
                }
                value
            }
            is PrefValue.BooleanValue, is PrefValue.LongValue -> value
            else -> throw IllegalArgumentException(
                "A flutter. key cannot hold a ${value.type.label}: Dart has no such type. " +
                    "It can hold: ${DART_TYPES.joinToString()}.",
            )
        }
    }

    private fun jsonList(json: String): List<String>? = try {
        val element = JsonParser.parseString(json)
        element.takeIf { it.isJsonArray }?.asJsonArray?.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) return null
            item.asString
        }
    } catch (_: JsonParseException) {
        null
    }

    private fun jsonEncoded(values: List<String>): String =
        JSON_LIST_PREFIX + PrefValue.StringListValue(values).text()

    /**
     * The legacy form: a base64 `java.util.ArrayList` of strings. Deserialising is restricted to
     * exactly those two classes, so a crafted file on the device cannot make the IDE instantiate
     * anything else.
     */
    // The stream is whatever the file holds, and a malformed one fails inside ArrayList and
    // ObjectInputStream with whichever runtime exception it reaches first. Any of them means
    // this one value cannot be read; none should cost the rest of the file.
    @Suppress("TooGenericExceptionCaught")
    private fun serializedList(base64: String): List<String>? {
        if (base64.length > LIST_MAX_BASE64_CHARS) return null
        return try {
            // Strict: the plugin's line breaks are the only thing that is not base64. A lenient
            // decoder would skip anything else and decode what is left as if it were the value.
            val bytes = Base64.getDecoder().decode(base64.filterNot(Char::isWhitespace))
            ObjectInputStream(ByteArrayInputStream(bytes)).use { stream ->
                stream.objectInputFilter = listFilter(bytes.size)
                (stream.readObject() as? ArrayList<*>)?.map { it as? String ?: return null }
            }
        } catch (_: IOException) {
            null
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun serializedEncoded(values: List<String>): String {
        val bytes = ByteArrayOutputStream().also { out ->
            ObjectOutputStream(out).use { it.writeObject(ArrayList(values)) }
        }.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return LIST_PREFIX + base64.chunked(BASE64_LINE).joinToString("\n", postfix = "\n")
    }

    /** `ArrayList.readObject` also asks about the `Object[]` it allocates for its elements. */
    private val LIST_CLASSES = setOf(ArrayList::class.java, String::class.java, Array<Any>::class.java)
    private const val LIST_MAX_DEPTH = 2L

    /** More than a preferences list holds, and few enough that allocating them costs nothing. */
    private const val LIST_MAX_ELEMENTS = 100_000

    /** The file-size limit App Storage reads and writes: no list in a file can be longer. */
    private const val LIST_MAX_BASE64_CHARS = 8 * 1024 * 1024

    /**
     * Only an `ArrayList` of strings, and nothing the stream does not pay for. `ArrayList` takes
     * its element count from the stream and allocates it before reading a single element, so a
     * few dozen bytes could otherwise declare 400 million of them. Every element, reference and
     * byte costs at least one byte of [streamSize], which bounds them all.
     */
    private fun listFilter(streamSize: Int): ObjectInputFilter {
        val maxElements = minOf(streamSize, LIST_MAX_ELEMENTS).toLong()
        return ObjectInputFilter { info ->
            val type = info.serialClass()
            when {
                info.depth() > LIST_MAX_DEPTH -> ObjectInputFilter.Status.REJECTED
                info.arrayLength() > maxElements -> ObjectInputFilter.Status.REJECTED
                info.references() > streamSize -> ObjectInputFilter.Status.REJECTED
                info.streamBytes() > streamSize -> ObjectInputFilter.Status.REJECTED
                type == null || type in LIST_CLASSES -> ObjectInputFilter.Status.ALLOWED
                else -> ObjectInputFilter.Status.REJECTED
            }
        }
    }
}

/**
 * How this file is read and written: by its kind, except that Flutter's own SharedPreferences
 * file decodes the plugin's encoded values.
 */
val StorageFile.format: PrefsFormat?
    get() = if (kind == StorageKind.SHARED_PREFERENCES && name == FlutterPrefsXml.FILE_NAME) {
        FlutterPrefsXml
    } else {
        kind.format
    }
