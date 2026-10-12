package spock.adb.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/** The version in gradle.properties, read off disk as [ReadmeToolCountTest] reads the README. */
internal object PluginVersion {
    fun fromGradleProperties(): String =
        File("gradle.properties").inputStream().use { Properties().apply { load(it) } }.getProperty("pluginVersion")
}

/**
 * `serverInfo.version` said `1.0.0` whatever the plugin was. Outside an IDE the plugin descriptor
 * cannot be read, so these run the fallback — the resource processResources writes — which is what
 * the stdio path and every test would otherwise report wrongly.
 */
class McpServerVersionTest {

    @Test
    fun `the version reported is the plugin version from gradle properties`() {
        val expected = PluginVersion.fromGradleProperties()

        assertTrue(expected.isNotBlank())
        assertEquals(expected, McpServerVersion.current)
    }

    @Test
    fun `processResources filled in the resource rather than shipping the placeholder`() {
        assertEquals(PluginVersion.fromGradleProperties(), McpServerVersion.fromResource())
    }

    @Test
    fun `the plugin id looked up is the one plugin xml declares`() {
        // A drifted id would make the descriptor lookup return null in the IDE, silently falling
        // back to the resource — which would hide the drift until the two versions disagreed.
        val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()
        val declared = Regex("<id>([^<]+)</id>").find(pluginXml)?.groupValues?.get(1)

        assertEquals(declared, McpServerVersion.PLUGIN_ID)
    }
}
