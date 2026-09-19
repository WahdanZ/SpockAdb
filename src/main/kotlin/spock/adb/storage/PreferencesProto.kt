package spock.adb.storage

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * `files/datastore/<name>.preferences_pb`: one protobuf `PreferenceMap`, read and written by
 * hand so the plugin carries no protobuf runtime.
 *
 * ```
 * message PreferenceMap { map<string, Value> preferences = 1; }
 * message Value {
 *   oneof valueName {
 *     bool boolean = 1;  float float = 2;  int32 integer = 3;  int64 long = 4;
 *     string string = 5; StringSet string_set = 6; double double = 7; bytes bytes = 8;
 *   }
 * }
 * message StringSet { repeated string strings = 1; }
 * ```
 *
 * A map is encoded as repeated field 1, each entry a message of key (1) and value (2).
 *
 * Nothing the editor does not understand is lost. Every top-level field is kept as the bytes it
 * was read as, and an entry that is not edited is written back as exactly those bytes — so an
 * unknown field anywhere inside it, or an encoding a different protobuf library chose, survives
 * untouched. An edited entry is rebuilt, and carries over the unknown fields of its entry, its
 * value and its string set.
 */
internal object PreferencesProto : PrefsFormat {

    override val types = PrefType.entries.toList()

    private sealed interface Field {
        val key: String?
    }

    /** A top-level field that is not a map entry. It has no key, so no change can address it. */
    private class Verbatim(val bytes: ByteArray) : Field {
        override val key: String? = null
    }

    private class Entry(
        override val key: String,
        /** Null when the value holds no member this editor knows. */
        val value: PrefValue?,
        /** The whole field as read. Null once rebuilt by an edit. */
        val original: ByteArray?,
        val unknownInEntry: ByteArray = EMPTY,
        val unknownInValue: ByteArray = EMPTY,
        val unknownInSet: ByteArray = EMPTY,
    ) : Field

    override fun read(bytes: ByteArray): List<PrefItem> = parse(bytes).filterIsInstance<Entry>().map { entry ->
        entry.value?.let { PrefItem.Typed(entry.key, it) }
            ?: PrefItem.Opaque(entry.key, "a value of a kind this editor does not know")
    }

    override fun write(original: ByteArray, changes: List<PrefChange>): ByteArray {
        val fields = parse(original).applying(
            changes,
            types,
            keyOf = { it.key },
            isEditable = { it is Entry && it.value != null },
            build = { key, value, previous ->
                val old = previous as? Entry
                Entry(
                    key = key,
                    value = value,
                    original = null,
                    unknownInEntry = old?.unknownInEntry ?: EMPTY,
                    unknownInValue = old?.unknownInValue ?: EMPTY,
                    unknownInSet = if (value is PrefValue.StringSetValue) old?.unknownInSet ?: EMPTY else EMPTY,
                )
            },
        )
        return WireWriter().apply { fields.forEach { writeField(it) } }.toByteArray()
    }

    // ---------------------------------------------------------------- reading

    private fun parse(bytes: ByteArray): List<Field> {
        val reader = WireReader(bytes, 0, bytes.size)
        val fields = mutableListOf<Field>()
        while (reader.hasMore) {
            val start = reader.position
            val (number, wireType) = reader.tag()
            if (number == MAP_FIELD) {
                val entry = reader.expect(number, wireType, LENGTH_DELIMITED).lengthDelimited()
                fields += parseEntry(entry, bytes.copyOfRange(start, reader.position))
            } else {
                reader.skip(wireType)
                fields += Verbatim(bytes.copyOfRange(start, reader.position))
            }
        }
        return fields
    }

    private fun parseEntry(reader: WireReader, original: ByteArray): Entry {
        var key = ""
        var value: WireReader? = null
        val unknown = ByteArrayOutputStream()
        while (reader.hasMore) {
            val start = reader.position
            val (number, wireType) = reader.tag()
            when (number) {
                ENTRY_KEY -> key = reader.expect(number, wireType, LENGTH_DELIMITED).lengthDelimited().string()
                ENTRY_VALUE -> value = reader.expect(number, wireType, LENGTH_DELIMITED).lengthDelimited()
                else -> {
                    reader.skip(wireType)
                    unknown.write(reader.bytesFrom(start))
                }
            }
        }
        // An entry with no value field holds the default Value, which has no member set.
        val decoded = value?.let(::parseValue) ?: DecodedValue(null, EMPTY, EMPTY)
        return Entry(key, decoded.value, original, unknown.toByteArray(), decoded.unknown, decoded.unknownInSet)
    }

    private class DecodedValue(val value: PrefValue?, val unknown: ByteArray, val unknownInSet: ByteArray)

    private fun parseValue(reader: WireReader): DecodedValue {
        var value: PrefValue? = null
        var unknownInSet = EMPTY
        val unknown = ByteArrayOutputStream()
        while (reader.hasMore) {
            val start = reader.position
            val (number, wireType) = reader.tag()
            val field = { expected: Int -> reader.expect(number, wireType, expected) }
            // A oneof member seen twice: the last one wins, as it does for protobuf itself.
            value = when (number) {
                VALUE_BOOLEAN -> PrefValue.BooleanValue(field(VARINT).varint() != 0L)
                VALUE_FLOAT -> PrefValue.FloatValue(Float.fromBits(field(FIXED32).fixed32()))
                VALUE_INT -> PrefValue.IntValue(field(VARINT).varint().toInt())
                VALUE_LONG -> PrefValue.LongValue(field(VARINT).varint())
                VALUE_STRING -> PrefValue.StringValue(field(LENGTH_DELIMITED).lengthDelimited().string())
                VALUE_STRING_SET -> {
                    val (set, unknownFields) = parseStringSet(field(LENGTH_DELIMITED).lengthDelimited())
                    unknownInSet = unknownFields
                    set
                }
                VALUE_DOUBLE -> PrefValue.DoubleValue(Double.fromBits(field(FIXED64).fixed64()))
                VALUE_BYTES -> PrefValue.BytesValue(field(LENGTH_DELIMITED).lengthDelimited().remaining())
                else -> {
                    reader.skip(wireType)
                    unknown.write(reader.bytesFrom(start))
                    value
                }
            }
        }
        return DecodedValue(value, unknown.toByteArray(), unknownInSet)
    }

    private fun parseStringSet(reader: WireReader): Pair<PrefValue.StringSetValue, ByteArray> {
        val strings = mutableListOf<String>()
        val unknown = ByteArrayOutputStream()
        while (reader.hasMore) {
            val start = reader.position
            val (number, wireType) = reader.tag()
            if (number == SET_STRINGS) {
                strings += reader.expect(number, wireType, LENGTH_DELIMITED).lengthDelimited().string()
            } else {
                reader.skip(wireType)
                unknown.write(reader.bytesFrom(start))
            }
        }
        return PrefValue.StringSetValue(strings) to unknown.toByteArray()
    }

    // ---------------------------------------------------------------- writing

    private fun WireWriter.writeField(field: Field) {
        when (field) {
            is Verbatim -> raw(field.bytes)
            is Entry -> field.original?.let(::raw) ?: bytes(MAP_FIELD, entryPayload(field))
        }
    }

    private fun entryPayload(entry: Entry): ByteArray = WireWriter().apply {
        bytes(ENTRY_KEY, entry.key.toByteArray(Charsets.UTF_8))
        bytes(ENTRY_VALUE, valuePayload(entry))
        raw(entry.unknownInEntry)
    }.toByteArray()

    /**
     * Unknown fields go first: if one is a oneof member from a newer schema, the member written
     * here comes after it, and so is the one a newer reader keeps.
     */
    private fun valuePayload(entry: Entry): ByteArray = WireWriter().apply {
        raw(entry.unknownInValue)
        when (val value = requireNotNull(entry.value)) {
            is PrefValue.BooleanValue -> varintField(VALUE_BOOLEAN, if (value.value) 1L else 0L)
            is PrefValue.FloatValue -> fixed32Field(VALUE_FLOAT, value.value.toRawBits())
            // Sign-extended, so a negative int32 is ten bytes, as protobuf requires.
            is PrefValue.IntValue -> varintField(VALUE_INT, value.value.toLong())
            is PrefValue.LongValue -> varintField(VALUE_LONG, value.value)
            is PrefValue.StringValue -> bytes(VALUE_STRING, value.value.toByteArray(Charsets.UTF_8))
            is PrefValue.StringSetValue -> bytes(VALUE_STRING_SET, stringSetPayload(value, entry.unknownInSet))
            is PrefValue.DoubleValue -> fixed64Field(VALUE_DOUBLE, value.value.toRawBits())
            is PrefValue.BytesValue -> bytes(VALUE_BYTES, value.value)
        }
    }.toByteArray()

    private fun stringSetPayload(value: PrefValue.StringSetValue, unknown: ByteArray): ByteArray = WireWriter().apply {
        value.values.forEach { bytes(SET_STRINGS, it.toByteArray(Charsets.UTF_8)) }
        raw(unknown)
    }.toByteArray()

    private const val MAP_FIELD = 1
    private const val ENTRY_KEY = 1
    private const val ENTRY_VALUE = 2
    private const val VALUE_BOOLEAN = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_INT = 3
    private const val VALUE_LONG = 4
    private const val VALUE_STRING = 5
    private const val VALUE_STRING_SET = 6
    private const val VALUE_DOUBLE = 7
    private const val VALUE_BYTES = 8
    private const val SET_STRINGS = 1

    private val EMPTY = ByteArray(0)
}

internal const val VARINT = 0
internal const val FIXED64 = 1
internal const val LENGTH_DELIMITED = 2
internal const val FIXED32 = 5

private const val WIRE_TYPE_BITS = 3
private const val WIRE_TYPE_MASK = 7L
private const val MAX_FIELD_NUMBER = (1L shl 29) - 1
private const val VARINT_PAYLOAD_BITS = 7
private const val VARINT_PAYLOAD_MASK = 0x7fL
private const val VARINT_CONTINUATION = 0x80L
private const val BYTE_MASK = 0xffL

/** The shift the tenth byte of a varint lands on: only its lowest bit is still inside 64 bits. */
private const val LAST_VARINT_SHIFT = 63

/**
 * Reads protobuf wire format from a window of [bytes], refusing anything malformed.
 *
 * Every length is checked against the window before it is used: the file comes off a device,
 * and a length that runs past the end is a corrupt file to report, not an index to trust.
 */
internal class WireReader(private val bytes: ByteArray, start: Int, private val end: Int) {

    var position: Int = start
        private set

    val hasMore: Boolean get() = position < end

    fun tag(): Pair<Int, Int> {
        val tag = varint()
        val number = tag ushr WIRE_TYPE_BITS
        if (number < 1 || number > MAX_FIELD_NUMBER) fail("field number $number is out of range")
        return number.toInt() to (tag and WIRE_TYPE_MASK).toInt()
    }

    fun expect(number: Int, actual: Int, expected: Int): WireReader {
        if (actual != expected) fail("field $number has wire type $actual where $expected was expected")
        return this
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < Long.SIZE_BITS) {
            val byte = next().toLong() and BYTE_MASK
            val payload = byte and VARINT_PAYLOAD_MASK
            // Only one bit of the tenth byte fits in 64. Accepting more would silently discard
            // it, reading a malformed value as a valid one rather than refusing the file.
            if (shift == LAST_VARINT_SHIFT && payload > 1L) fail("a varint does not fit in 64 bits")
            result = result or (payload shl shift)
            if (byte and VARINT_CONTINUATION == 0L) return result
            shift += VARINT_PAYLOAD_BITS
        }
        fail("a varint is longer than ten bytes")
    }

    fun fixed32(): Int = littleEndian(Int.SIZE_BYTES).toInt()

    fun fixed64(): Long = littleEndian(Long.SIZE_BYTES)

    /** A reader over the next length-delimited payload, which this reader then steps past. */
    fun lengthDelimited(): WireReader {
        val length = varint()
        if (length < 0 || length > end - position) fail("a length of $length runs past the end")
        val payload = WireReader(bytes, position, position + length.toInt())
        position += length.toInt()
        return payload
    }

    fun skip(wireType: Int) {
        when (wireType) {
            VARINT -> varint()
            FIXED64 -> littleEndian(Long.SIZE_BYTES)
            LENGTH_DELIMITED -> lengthDelimited()
            FIXED32 -> littleEndian(Int.SIZE_BYTES)
            else -> fail("wire type $wireType is not one a PreferenceMap uses")
        }
    }

    /** The rest of this window. */
    fun remaining(): ByteArray = bytes.copyOfRange(position, end).also { position = end }

    fun bytesFrom(start: Int): ByteArray = bytes.copyOfRange(start, position)

    /** The rest of this window as strict UTF-8, as protobuf requires of a `string`. */
    fun string(): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(remaining()))
            .toString()
    } catch (e: CharacterCodingException) {
        throw PrefsFormatException("Not a Preferences DataStore file: a string is not valid UTF-8.", e)
    }

    private fun littleEndian(size: Int): Long {
        var result = 0L
        repeat(size) { index -> result = result or ((next().toLong() and BYTE_MASK) shl (index * Byte.SIZE_BITS)) }
        return result
    }

    private fun next(): Byte {
        if (position >= end) fail("the data ends in the middle of a field")
        return bytes[position++]
    }

    private fun fail(reason: String): Nothing =
        throw PrefsFormatException("Not a Preferences DataStore file: $reason.")
}

internal class WireWriter {
    private val out = ByteArrayOutputStream()

    fun raw(bytes: ByteArray) = out.write(bytes)

    fun varintField(number: Int, value: Long) {
        tag(number, VARINT)
        varint(value)
    }

    fun fixed32Field(number: Int, value: Int) {
        tag(number, FIXED32)
        littleEndian(value.toLong(), Int.SIZE_BYTES)
    }

    fun fixed64Field(number: Int, value: Long) {
        tag(number, FIXED64)
        littleEndian(value, Long.SIZE_BYTES)
    }

    fun bytes(number: Int, value: ByteArray) {
        tag(number, LENGTH_DELIMITED)
        varint(value.size.toLong())
        out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(number: Int, wireType: Int) = varint(((number shl WIRE_TYPE_BITS) or wireType).toLong())

    private fun varint(value: Long) {
        var remaining = value
        while (remaining and VARINT_PAYLOAD_MASK.inv() != 0L) {
            out.write(((remaining and VARINT_PAYLOAD_MASK) or VARINT_CONTINUATION).toInt())
            remaining = remaining ushr VARINT_PAYLOAD_BITS
        }
        out.write(remaining.toInt())
    }

    private fun littleEndian(value: Long, size: Int) {
        repeat(size) { out.write((value ushr (it * Byte.SIZE_BITS) and BYTE_MASK).toInt()) }
    }
}
