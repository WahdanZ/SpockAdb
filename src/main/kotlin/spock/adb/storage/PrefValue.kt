package spock.adb.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.util.Base64

/** The value types SharedPreferences and Preferences DataStore can hold. */
enum class PrefType(val label: String) {
    BOOLEAN("boolean"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    STRING("string"),
    STRING_SET("string set"),
    BYTES("bytes"),
    ;

    /** The spelling agents use: `string_set` rather than `string set`. */
    val argument: String get() = name.lowercase()

    override fun toString(): String = label

    companion object {
        fun fromName(name: String): PrefType? = name.trim().let { given ->
            entries.firstOrNull {
                it.argument.equals(given, ignoreCase = true) || it.label.equals(given, ignoreCase = true)
            }
        }
    }
}

/**
 * One typed value.
 *
 * Every value has a text form — what the table shows and what an agent sends — and [parse]
 * reads it back strictly. A value that does not parse is refused rather than coerced: writing
 * `1` for "1.5" into an int silently changes app state the developer did not ask to change.
 */
sealed interface PrefValue {
    val type: PrefType

    fun text(): String

    data class BooleanValue(val value: Boolean) : PrefValue {
        override val type get() = PrefType.BOOLEAN
        override fun text() = value.toString()
    }

    data class IntValue(val value: Int) : PrefValue {
        override val type get() = PrefType.INT
        override fun text() = value.toString()
    }

    data class LongValue(val value: Long) : PrefValue {
        override val type get() = PrefType.LONG
        override fun text() = value.toString()
    }

    /** Equality follows [Float.compareTo], so NaN equals NaN and -0.0 is not 0.0 — as the file does. */
    data class FloatValue(val value: Float) : PrefValue {
        override val type get() = PrefType.FLOAT
        override fun text() = value.toString()
    }

    data class DoubleValue(val value: Double) : PrefValue {
        override val type get() = PrefType.DOUBLE
        override fun text() = value.toString()
    }

    data class StringValue(val value: String) : PrefValue {
        override val type get() = PrefType.STRING
        override fun text() = value
    }

    /** A list rather than a set so the order the file holds is the order it is written back in. */
    data class StringSetValue(val values: List<String>) : PrefValue {
        override val type get() = PrefType.STRING_SET
        override fun text(): String = GSON.toJson(values)
    }

    class BytesValue(val value: ByteArray) : PrefValue {
        override val type get() = PrefType.BYTES
        override fun text(): String = Base64.getEncoder().encodeToString(value)
        override fun equals(other: Any?) = other is BytesValue && other.value.contentEquals(value)
        override fun hashCode() = value.contentHashCode()
        override fun toString() = "BytesValue(${text()})"
    }

    companion object {
        /** @throws IllegalArgumentException naming what the text should have looked like. */
        fun parse(type: PrefType, text: String): PrefValue = when (type) {
            PrefType.BOOLEAN -> BooleanValue(boolean(text))
            PrefType.INT -> IntValue(whole(text, "an int", Int.MIN_VALUE, Int.MAX_VALUE, String::toIntOrNull))
            PrefType.LONG -> LongValue(whole(text, "a long", Long.MIN_VALUE, Long.MAX_VALUE, String::toLongOrNull))
            PrefType.FLOAT -> FloatValue(decimal(text, "float", String::toFloat, Float::isInfinite))
            PrefType.DOUBLE -> DoubleValue(decimal(text, "double", String::toDouble, Double::isInfinite))
            PrefType.STRING -> StringValue(text)
            PrefType.STRING_SET -> StringSetValue(stringSet(text))
            PrefType.BYTES -> BytesValue(bytes(text))
        }

        private fun boolean(text: String): Boolean = when (text.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("'$text' is not a boolean. Use true or false.")
        }

        private fun <T : Any> whole(text: String, what: String, min: T, max: T, convert: (String) -> T?): T =
            requireNotNull(convert(text.trim())) { "'$text' is not $what: a whole number from $min to $max." }

        /**
         * The decimal spellings a person means, and the three non-finite ones the file can hold.
         * Java's own parser would also take `1.5f`, `0x1p3`, and a finite number too large to hold,
         * which it quietly turns into Infinity.
         */
        private fun <T> decimal(text: String, what: String, convert: (String) -> T, isInfinite: (T) -> Boolean): T {
            val trimmed = text.trim()
            require(trimmed in NON_FINITE || DECIMAL.matches(trimmed)) {
                "'$text' is not a $what, such as 1.5 or -2e3."
            }
            val value = convert(trimmed)
            require(!isInfinite(value) || trimmed.trimStart('-', '+') == "Infinity") {
                "'$text' is out of range for a $what."
            }
            return value
        }

        private fun bytes(text: String): ByteArray = try {
            Base64.getDecoder().decode(text.filterNot(Char::isWhitespace))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("'$text' is not base64, which is how bytes are written.", e)
        }

        private fun stringSet(text: String): List<String> {
            val invalid = "'$text' is not a string set. Write it as a JSON array of strings, such as [\"a\", \"b\"]."
            val element = try {
                JsonParser.parseString(text)
            } catch (e: JsonParseException) {
                throw IllegalArgumentException(invalid, e)
            }
            require(element.isJsonArray) { invalid }
            val values = element.asJsonArray.map { item ->
                require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { invalid }
                item.asString
            }
            val repeated = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            require(repeated.isEmpty()) { "A string set holds each value once; repeated: ${repeated.joinToString()}." }
            return values
        }

        private val DECIMAL = Regex("""[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
        private val NON_FINITE = setOf("NaN", "Infinity", "-Infinity", "+Infinity")

        /** No HTML escaping: `<` in a value should read as `<`, not `<`. */
        private val GSON = GsonBuilder().disableHtmlEscaping().create()
    }
}
