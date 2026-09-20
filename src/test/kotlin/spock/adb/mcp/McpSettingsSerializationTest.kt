package spock.adb.mcp

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The token moved from this file into `PasswordSafe`, and the only thing standing between an
 * existing developer and a silently dead HTTP client is that the old attribute is still read.
 * Renaming the property without `@OptionTag` would orphan every `spock-adb-mcp.xml` already on
 * disk — leaving the secret in the file *and* losing the token.
 */
class McpSettingsSerializationTest {

    @Test
    fun `reads a token written by an earlier version`() {
        val element = JDOMUtil.load(
            """
            <McpSettings>
              <option name="port" value="63342" />
              <option name="token" value="legacy-token" />
            </McpSettings>
            """.trimIndent(),
        )

        val settings = XmlSerializer.deserialize(element, McpSettings::class.java)

        assertEquals("legacy-token", settings.legacyToken)
        assertEquals(63342, settings.port)
    }

    /** Proves the read above is the annotation working, not the property name matching by luck. */
    @Test
    fun `a token still awaiting migration keeps the name old files use`() {
        val settings = McpSettings(legacyToken = "not-yet-migrated")

        val xml = JDOMUtil.write(XmlSerializer.serialize(settings, SkipDefaultsSerializationFilter()))

        assertTrue(
            xml.contains("""<option name="token" value="not-yet-migrated" />"""),
            "an unmigrated token must round-trip under its old name: $xml",
        )
    }

    @Test
    fun `writes no token once it has been migrated out`() {
        val settings = McpSettings(port = 63342, legacyToken = "")

        // The filter the platform itself uses when it writes a PersistentStateComponent: a
        // value equal to the default is left out of the file entirely.
        val xml = JDOMUtil.write(XmlSerializer.serialize(settings, SkipDefaultsSerializationFilter()))

        assertFalse(xml.contains("token"), "a migrated settings file must not mention a token: $xml")
    }
}
