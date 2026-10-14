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
 * `serverInfo.version` said `1.0.0` whatever the plugin was. It now comes from the resource
 * processResources writes, the same in the IDE, on the stdio path and in every test.
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
}
