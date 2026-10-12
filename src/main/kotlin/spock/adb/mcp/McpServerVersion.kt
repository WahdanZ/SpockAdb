package spock.adb.mcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.util.Properties

/**
 * The version `initialize` reports as `serverInfo.version`: the plugin's own, so a client log
 * says which Spock build answered. It said `1.0.0` whatever the plugin was.
 *
 * Read from the installed plugin's descriptor first. Outside a running IDE — unit tests, or a
 * class loaded before the plugin set is ready — that lookup has nothing to return, so the
 * fallback is a resource Gradle's `processResources` writes from `pluginVersion`, the same value
 * the descriptor is built from.
 */
object McpServerVersion {

    /** The `<id>` in plugin.xml, exactly as written there. */
    const val PLUGIN_ID = "com.wahdan.com.wahdan.spockAdb"

    internal const val RESOURCE = "/spock/adb/mcp/plugin-version.properties"

    private const val UNKNOWN = "unknown"

    val current: String by lazy { fromPlugin() ?: fromResource() ?: UNKNOWN }

    /**
     * Null outside a running IDE. Whatever the platform throws there — no plugin set yet, or a
     * class it cannot initialise — means the same thing, and the resource fallback covers it.
     */
    private fun fromPlugin(): String? =
        runCatching { PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version }.getOrNull()

    internal fun fromResource(): String? =
        McpServerVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }?.takeIf { it.isNotBlank() && !it.contains("\${") }
}
