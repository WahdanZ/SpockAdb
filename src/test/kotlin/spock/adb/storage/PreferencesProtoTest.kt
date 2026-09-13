package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PreferencesProtoTest {

    @Test
    fun `reads the bytes DataStore writes`() {
        // {"a": true}: map field 1 { key 1: "a", value 2: { boolean 1: true } }
        assertEquals(
            listOf(PrefItem.Typed("a", PrefValue.BooleanValue(true))),
            PreferencesProto.read(hex("0a 07 0a 01 61 12 02 08 01")),
        )
    }

    @Test
    fun `an empty file is an empty map`() {
        assertEquals(emptyList<PrefItem>(), PreferencesProto.read(ByteArray(0)))
    }

    @Test
    fun `writes each type in its wire encoding`() {
        fun valueBytes(value: PrefValue): ByteArray {
            val written = PreferencesProto.write(ByteArray(0), listOf(PrefChange.Put("k", value)))
            // 0a <len> 0a 01 6b 12 <len> <value payload>
            return written.copyOfRange(7, written.size)
        }

        assertArrayEquals(hex("08 00"), valueBytes(PrefValue.BooleanValue(false)))
        assertArrayEquals(hex("15 00 00 c0 3f"), valueBytes(PrefValue.FloatValue(1.5f)))
        // A negative int32 is sign-extended to ten bytes; five would be read back as positive.
        assertArrayEquals(hex("18 ff ff ff ff ff ff ff ff ff 01"), valueBytes(PrefValue.IntValue(-1)))
        assertArrayEquals(hex("20 96 01"), valueBytes(PrefValue.LongValue(150)))
        assertArrayEquals(hex("2a 02 68 69"), valueBytes(PrefValue.StringValue("hi")))
        assertArrayEquals(hex("32 06 0a 01 61 0a 01 62"), valueBytes(PrefValue.StringSetValue(listOf("a", "b"))))
        assertArrayEquals(hex("39 00 00 00 00 00 00 f8 3f"), valueBytes(PrefValue.DoubleValue(1.5)))
        assertArrayEquals(hex("42 02 00 ff"), valueBytes(PrefValue.BytesValue(hex("00 ff"))))
    }

    @Test
    fun `every type round trips, including the awkward values`() {
        val values = listOf(
            PrefChange.Put("bool", PrefValue.BooleanValue(true)),
            PrefChange.Put("int_min", PrefValue.IntValue(Int.MIN_VALUE)),
            PrefChange.Put("long_max", PrefValue.LongValue(Long.MAX_VALUE)),
            PrefChange.Put("long_neg", PrefValue.LongValue(-1)),
            PrefChange.Put("nan", PrefValue.FloatValue(Float.NaN)),
            PrefChange.Put("neg_zero", PrefValue.DoubleValue(-0.0)),
            PrefChange.Put("text", PrefValue.StringValue("أحمد 😀")),
            PrefChange.Put("empty", PrefValue.StringValue("")),
            PrefChange.Put("", PrefValue.StringSetValue(emptyList())),
            PrefChange.Put("blob", PrefValue.BytesValue(ByteArray(300) { it.toByte() })),
        )

        val written = PreferencesProto.write(ByteArray(0), values)

        assertEquals(values.map { PrefItem.Typed(it.key, it.value) }, PreferencesProto.read(written))
        assertArrayEquals(written, PreferencesProto.write(written, emptyList()))
        // Data class equality on Float and Double follows compareTo, so this is a real check.
        assertNotEquals(PrefValue.DoubleValue(-0.0), PrefValue.DoubleValue(0.0))
    }

    @Test
    fun `unknown fields at every level survive editing that entry`() {
        val file = hex(
            // unknown top-level field 15, varint 42
            "78 2a" +
                // entry { key "a", value { unknown field 20 = 7, int 3: 5 }, unknown entry field 3 = 1 }
                " 0a 0c 0a 01 61 12 05 a0 01 07 18 05 18 01" +
                // entry { key "s", value { string_set { "x", unknown field 2 = 9 } } }
                " 0a 0c 0a 01 73 12 07 32 05 0a 01 78 10 09",
        )

        val written = PreferencesProto.write(
            file,
            listOf(
                PrefChange.Put("a", PrefValue.IntValue(6)),
                PrefChange.Put("s", PrefValue.StringSetValue(listOf("y"))),
            ),
        )

        assertArrayEquals(
            hex(
                "78 2a" +
                    " 0a 0c 0a 01 61 12 05 a0 01 07 18 06 18 01" +
                    " 0a 0c 0a 01 73 12 07 32 05 0a 01 79 10 09",
            ),
            written,
        )
    }

    @Test
    fun `an entry nobody edits is kept as the exact bytes it was read as`() {
        // Non-canonical but valid: the key encoded with a two-byte length (0x81 0x00 = 1).
        val file = hex("0a 08 0a 81 00 61 12 02 08 01") + hex("0a 07 0a 01 62 12 02 08 00")

        val written = PreferencesProto.write(file, listOf(PrefChange.Put("b", PrefValue.BooleanValue(true))))

        assertArrayEquals(hex("0a 08 0a 81 00 61 12 02 08 01") + hex("0a 07 0a 01 62 12 02 08 01"), written)
    }

    @Test
    fun `a value of an unknown kind is shown, preserved, and cannot be edited`() {
        // Value holds only field 9, a member this editor has never heard of.
        val file = hex("0a 08 0a 01 6e 12 03 48 96 01")

        assertEquals(
            listOf(PrefItem.Opaque("n", "a value of a kind this editor does not know")),
            PreferencesProto.read(file),
        )
        assertArrayEquals(file, PreferencesProto.write(file, emptyList()))
        assertThrows<IllegalArgumentException> {
            PreferencesProto.write(file, listOf(PrefChange.Put("n", PrefValue.IntValue(1))))
        }
    }

    @Test
    fun `a repeated key reads as the last value and an edit leaves one entry`() {
        val file = hex("0a 07 0a 01 61 12 02 08 00") + hex("0a 07 0a 01 61 12 02 08 01")

        assertEquals(
            listOf(
                PrefItem.Typed("a", PrefValue.BooleanValue(false)),
                PrefItem.Typed("a", PrefValue.BooleanValue(true)),
            ),
            PreferencesProto.read(file),
        )
        assertEquals(
            listOf(PrefItem.Typed("a", PrefValue.IntValue(1))),
            PreferencesProto.read(PreferencesProto.write(file, listOf(PrefChange.Put("a", PrefValue.IntValue(1))))),
        )
        val removed = PreferencesProto.write(file, listOf(PrefChange.Remove("a")))
        assertEquals(emptyList<PrefItem>(), PreferencesProto.read(removed))
    }

    @Test
    fun `removing an entry keeps the others in order`() {
        val file = PreferencesProto.write(
            ByteArray(0),
            listOf("a", "b", "c").map { PrefChange.Put(it, PrefValue.IntValue(1)) },
        )

        val removed = PreferencesProto.write(file, listOf(PrefChange.Remove("b")))
        assertEquals(listOf("a", "c"), PreferencesProto.read(removed).map { it.key })
    }

    @Test
    fun `corrupt data is refused, never partly read`() {
        listOf(
            "0a" to "truncated tag payload",
            "0a 07 0a 01 61" to "length past the end",
            "0a 05 0a 09 61 61 61" to "inner length past the end",
            "78 ff ff ff ff ff ff ff ff ff ff 01" to "varint longer than ten bytes",
            "7b" to "a group",
            "08 01" to "map field with the wrong wire type",
            "0a 05 0a 03 c3 28 61" to "invalid UTF-8 key",
            "0a 04 12 02 08 ff" to "truncated varint inside a value",
            "00 00" to "field number zero",
        ).forEach { (bytes, why) ->
            assertThrows<PrefsFormatException>(why) { PreferencesProto.read(hex(bytes)) }
        }
    }

    private fun hex(text: String): ByteArray =
        text.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
