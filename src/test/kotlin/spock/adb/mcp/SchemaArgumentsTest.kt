package spock.adb.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import spock.adb.mcp.tools.optionalInt
import spock.adb.mcp.tools.requiredInt

/**
 * The whole-number accessors refuse what they used to quietly reshape. Arguments are parsed
 * from JSON text rather than built with `addProperty`, because that is how they arrive from a
 * client — as Gson's lazily parsed numbers, not as Kotlin Ints.
 */
class SchemaArgumentsTest {

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun rejected(json: String): String =
        assertThrows<IllegalArgumentException> { args(json).requiredInt("port") }.message.orEmpty()

    @Test
    fun `a whole number is read as-is`() {
        assertEquals(8888, args("""{"port":8888}""").requiredInt("port"))
    }

    @Test
    fun `a number with a zero fraction is a whole number`() {
        // JSON Schema's integer accepts 8888.0, so refusing it would reject a valid call.
        assertEquals(8888, args("""{"port":8888.0}""").requiredInt("port"))
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
        assertEquals(500, args("{}").optionalInt("durationMs", 500))
        assertEquals(500, args("""{"durationMs":null}""").optionalInt("durationMs", 500))
    }

    @Test
    fun `a present optional number is held to the same rules`() {
        assertEquals(750, args("""{"durationMs":750}""").optionalInt("durationMs", 500))
        assertThrows<IllegalArgumentException> { args("""{"durationMs":"750"}""").optionalInt("durationMs", 500) }
    }
}
