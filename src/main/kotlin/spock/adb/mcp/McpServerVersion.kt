package spock.adb.mcp

import java.util.Properties

/**
 * The version `initialize` reports as `serverInfo.version`: the plugin's own, so a client log
 * says which Spock build answered. It said `1.0.0` whatever the plugin was.
 *
 * Read from a resource Gradle's `processResources` writes from `pluginVersion`, the same value
 * the plugin descriptor is built from. It used to ask the platform for the installed descriptor
 * first, but every lookup that does that — `PluginManagerCore.getPlugin`,
 * `PluginManager.getPluginByClass` — is `@ApiStatus.Internal` as of IntelliJ IDEA 2026.2, and the
 * Marketplace verifier reports it. The resource gives the same answer in and out of the IDE.
 */
object McpServerVersion {

    internal const val RESOURCE = "/spock/adb/mcp/plugin-version.properties"

    private const val UNKNOWN = "unknown"

    val current: String by lazy { fromResource() ?: UNKNOWN }

    internal fun fromResource(): String? =
        McpServerVersion::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        }?.takeIf { it.isNotBlank() && !it.contains("\${") }
}
