package spock.adb.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrefValueTest {

    @Test
    fun `text and parse are inverses for every type`() {
        listOf(
            PrefValue.BooleanValue(true),
            PrefValue.IntValue(Int.MIN_VALUE),
            PrefValue.LongValue(Long.MAX_VALUE),
            PrefValue.FloatValue(1.5f),
            PrefValue.FloatValue(Float.NaN),
            PrefValue.DoubleValue(Double.NEGATIVE_INFINITY),
            PrefValue.StringValue("  kept as typed <&> "),
            PrefValue.StringSetValue(listOf("a", "quote \" and <tag>", "")),
            PrefValue.BytesValue(byteArrayOf(0, -1, 7)),
        ).forEach { value ->
            assertEquals(value, PrefValue.parse(value.type, value.text()), value.toString())
        }
    }

    @Test
    fun `a string set is shown as readable JSON`() {
        assertEquals("[\"a\",\"<b>\"]", PrefValue.StringSetValue(listOf("a", "<b>")).text())
    }

    @Test
    fun `values that do not fit the type are refused, not coerced`() {
        listOf(
            PrefType.BOOLEAN to "yes",
            PrefType.INT to "1.5",
            PrefType.INT to "2147483648",
            PrefType.LONG to "",
            PrefType.FLOAT to "1.5f",
            PrefType.FLOAT to "0x1p3",
            PrefType.FLOAT to "1e39",
            PrefType.DOUBLE to "abc",
            PrefType.STRING_SET to "a, b",
            PrefType.STRING_SET to "[1, 2]",
            PrefType.STRING_SET to "[\"a\", \"a\"]",
            PrefType.BYTES to "not base64!",
        ).forEach { (type, text) ->
            assertThrows<IllegalArgumentException>("$type '$text'") { PrefValue.parse(type, text) }
        }
    }

    @Test
    fun `a string list keeps its order and may repeat a value`() {
        assertEquals(
            PrefValue.StringListValue(listOf("b", "a", "b", "")),
            PrefValue.parse(PrefType.STRING_LIST, "[\"b\", \"a\", \"b\", \"\"]"),
        )
        assertEquals(PrefValue.StringListValue(emptyList()), PrefValue.parse(PrefType.STRING_LIST, "[]"))
        val list = PrefValue.StringListValue(listOf("quote \" and <tag>", "x"))
        assertEquals(list, PrefValue.parse(PrefType.STRING_LIST, list.text()))
    }

    @Test
    fun `a string list is a JSON array of strings, or refused`() {
        listOf("a, b", "[\"a\"", "[\"a\", 1]", "[null]", "[[\"a\"]]", "{}", "\"a\"").forEach { text ->
            val thrown = assertThrows<IllegalArgumentException>(text) { PrefValue.parse(PrefType.STRING_LIST, text) }
            assertTrue(thrown.message!!.contains("is not a string list"), thrown.message)
        }
    }

    @Test
    fun `types are found by the agent spelling and the table spelling`() {
        assertEquals(PrefType.STRING_SET, PrefType.fromName("string_set"))
        assertEquals(PrefType.STRING_SET, PrefType.fromName("string set"))
        assertEquals(PrefType.INT, PrefType.fromName(" INT "))
        assertEquals(null, PrefType.fromName("integer"))
    }
}
