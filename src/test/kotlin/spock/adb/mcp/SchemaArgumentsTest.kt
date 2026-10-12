package spock.adb.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.mcp.tools.Schema
import spock.adb.mcp.tools.optionalInt
import spock.adb.mcp.tools.optionalNumber
import spock.adb.mcp.tools.requiredInt

/**
 * The whole-number accessors refuse what they used to quietly reshape. Arguments are parsed
 * from JSON text rather than built with `addProperty`, because that is how they arrive from a
 * client — as Gson's lazily parsed numbers, not as Kotlin Ints.
 */
class SchemaArgumentsTest {

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun rejected(json: String): String =
        assertThrows<IllegalArgumentException> { args(json).requiredInt("port", ANY) }.message.orEmpty()

    @Test
    fun `a whole number is read as-is`() {
        assertEquals(8888, args("""{"port":8888}""").requiredInt("port", ANY))
    }

    @Test
    fun `a number with a zero fraction is a whole number`() {
        // JSON Schema's integer accepts 8888.0, so refusing it would reject a valid call.
        assertEquals(8888, args("""{"port":8888.0}""").requiredInt("port", ANY))
    }

    @Test
    fun `a fractional number is refused, not truncated`() {
        val message = rejected("""{"port":8888.9}""")

        assertTrue(message.contains("'port'") && message.contains("whole number"), message)
    }

    @Test
    fun `a fraction too small for a Double to see is still refused`() {
        // Read through a Double this rounds to exactly Int.MAX_VALUE and was accepted.
        val message = rejected("""{"port":2147483647.0000001}""")

        assertTrue(message.contains("whole number"), message)
    }

    @Test
    fun `a number outside Int range is refused rather than clamped`() {
        val message = rejected("""{"port":1e10}""")

        assertTrue(message.contains("'port'") && message.contains("out of range"), message)
    }

    @Test
    fun `a number sent as a string is refused`() {
        val message = rejected("""{"port":"8888"}""")

        assertTrue(message.contains("'port'") && message.contains("\"8888\""), message)
    }

    @Test
    fun `a boolean is refused`() {
        assertTrue(rejected("""{"port":true}""").contains("'port'"))
    }

    @Test
    fun `an object or array is refused`() {
        assertTrue(rejected("""{"port":{"value":8888}}""").contains("an object"))
        assertTrue(rejected("""{"port":[8888]}""").contains("an array"))
    }

    @Test
    fun `a missing required number says it is missing`() {
        val message = rejected("{}")

        assertTrue(message.startsWith("Missing required argument 'port'"), message)
    }

    @Test
    fun `a missing or null optional number takes the default`() {
        assertEquals(500, args("{}").optionalInt("durationMs", 500, ANY))
        assertEquals(500, args("""{"durationMs":null}""").optionalInt("durationMs", 500, ANY))
    }

    @Test
    fun `a present optional number is held to the same rules`() {
        assertEquals(750, args("""{"durationMs":750}""").optionalInt("durationMs", 500, ANY))
        assertThrows<IllegalArgumentException> { args("""{"durationMs":"750"}""").optionalInt("durationMs", 500, ANY) }
    }

    @Test
    fun `a whole number outside its bounds is refused, naming the argument and the range`() {
        val message = assertThrows<IllegalArgumentException> {
            args("""{"maxLines":-5}""").optionalInt("maxLines", 300, 1..5_000)
        }.message

        assertEquals("Argument 'maxLines' must be between 1 and 5000, got -5.", message)
    }

    @Test
    fun `both ends of the bounds are values, not refusals`() {
        assertEquals(1, args("""{"level":1}""").requiredInt("level", 1..100))
        assertEquals(100, args("""{"level":100}""").requiredInt("level", 1..100))
        assertThrows<IllegalArgumentException> { args("""{"level":101}""").requiredInt("level", 1..100) }
    }

    @Test
    fun `the default is not held to the bounds, only a value that was sent`() {
        assertEquals(0, args("{}").optionalInt("timeoutMs", 0, 0..60_000))
        assertEquals(null, args("{}").optionalInt("port", 1..65_535))
    }

    @Test
    fun `a number with a fraction is read within its bounds`() {
        assertEquals(0.5, args("""{"scale":0.5}""").optionalNumber("scale", 1.0, 0.1..1.0))
        assertEquals(1.0, args("{}").optionalNumber("scale", 1.0, 0.1..1.0))

        val outside = assertThrows<IllegalArgumentException> {
            args("""{"scale":1.5}""").optionalNumber("scale", 1.0, 0.1..1.0)
        }.message
        assertEquals("Argument 'scale' must be between 0.1 and 1.0, got 1.5.", outside)

        val text = assertThrows<IllegalArgumentException> {
            args("""{"scale":"0.5"}""").optionalNumber("scale", 1.0, 0.1..1.0)
        }.message
        assertEquals("Argument 'scale' must be a number, got \"0.5\".", text)
    }

    @Test
    fun `the schema declares the bounds the reader enforces`() {
        val schema = Schema.obj {
            integer("maxLines", "Lines.", 1..5_000)
            number("scale", "Scale.", 0.1..1.0)
        }.getAsJsonObject("properties")

        assertEquals(1, schema.getAsJsonObject("maxLines").get("minimum").asInt)
        assertEquals(5_000, schema.getAsJsonObject("maxLines").get("maximum").asInt)
        assertEquals(0.1, schema.getAsJsonObject("scale").get("minimum").asDouble)
        assertEquals(1.0, schema.getAsJsonObject("scale").get("maximum").asDouble)
    }

    private companion object {
        val ANY = Int.MIN_VALUE..Int.MAX_VALUE
    }
}
