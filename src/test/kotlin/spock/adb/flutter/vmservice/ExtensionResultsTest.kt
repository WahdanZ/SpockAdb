package spock.adb.flutter.vmservice

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExtensionResultsTest {

    private fun bool(json: String, key: String = "enabled") =
        ExtensionResults.bool(JsonParser.parseString(json).asJsonObject, key)

    @Test
    fun `dart io answers with a JSON boolean`() {
        assertEquals(false, bool("""{"type":"HttpTimelineLoggingState","enabled":false}"""))
        assertEquals(true, bool("""{"type":"HttpTimelineLoggingState","enabled":true}"""))
    }

    @Test
    fun `Flutter answers with a string`() {
        val structuredErrors =
            """{"type":"_extensionType","method":"ext.flutter.inspector.structuredErrors","enabled":"true"}"""
        assertEquals(true, bool(structuredErrors))
        assertEquals(false, bool("""{"type":"_extensionType","enabled":"false"}"""))
    }

    @Test
    fun `a state-changed event's value reads the same way`() {
        assertEquals(true, bool("""{"extension":"ext.flutter.inspector.structuredErrors","value":"true"}""", "value"))
    }

    @Test
    fun `anything else is unknown, not false`() {
        assertNull(bool("""{"type":"Success"}"""))
        assertNull(bool("""{"enabled":"maybe"}"""))
        assertNull(bool("""{"enabled":1}"""))
        assertNull(bool("""{"enabled":null}"""))
    }
}
