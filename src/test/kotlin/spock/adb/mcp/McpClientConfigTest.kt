package spock.adb.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The security-relevant half of the client configuration: whether a credential ends up in text
 * a developer is about to put on a clipboard, and whether installing over an existing file
 * keeps the servers already in it.
 */
class McpClientConfigTest {

    private fun authorization(config: String): String =
        JsonParser.parseString(config).asJsonObject
            .getAsJsonObject("mcpServers")
            .getAsJsonObject(McpClientConfig.SERVER_NAME)
            .getAsJsonObject("headers")
            .get("Authorization").asString

    @Test
    fun `http config carries no token by default`() {
        val config = McpClientConfig.document(McpClientConfig.httpServer(port = 63342))

        assertEquals("Bearer \${${McpClientConfig.TOKEN_ENV_VAR}}", authorization(config))
        assertFalse(config.contains("secret-token"))
    }

    @Test
    fun `http config embeds the token only when asked`() {
        val config = McpClientConfig.document(
            McpClientConfig.httpServer(port = 63342, token = "secret-token"),
        )

        assertEquals("Bearer secret-token", authorization(config))
    }

    @Test
    fun `stdio config contains no credential`() {
        val config = McpClientConfig.document(
            McpClientConfig.stdioServer(
                javaExecutable = "/opt/jbr/bin/java",
                classpath = "/plugins/spock-adb/lib/spock-adb.jar",
                launcherClass = "spock.adb.mcp.stdio.SpockAdbStdioLauncher",
                descriptor = "/config/spock-adb/mcp-stdio.properties",
            ),
        )

        assertFalse(config.contains("Authorization"))
        assertFalse(config.contains("Bearer"))
        assertTrue(config.contains("mcp-stdio.properties"))
    }

    /** A Windows path through a hand-rolled JSON string would produce an invalid `C:\Users`. */
    @Test
    fun `stdio config escapes windows paths`() {
        val config = McpClientConfig.document(
            McpClientConfig.stdioServer(
                javaExecutable = """C:\Users\dev\jbr\bin\java.exe""",
                classpath = """C:\plugins\spock-adb.jar""",
                launcherClass = "spock.adb.mcp.stdio.SpockAdbStdioLauncher",
                descriptor = """C:\config\mcp-stdio.properties""",
            ),
        )

        val command = JsonParser.parseString(config).asJsonObject
            .getAsJsonObject("mcpServers")
            .getAsJsonObject(McpClientConfig.SERVER_NAME)
            .get("command").asString
        assertEquals("""C:\Users\dev\jbr\bin\java.exe""", command)
    }

    @Test
    fun `merge keeps other servers`() {
        val existing = """
            {
              "mcpServers": {
                "something-else": { "command": "node", "args": ["server.js"] }
              }
            }
        """.trimIndent()

        val merged = JsonParser.parseString(
            McpClientConfig.merge(existing, McpClientConfig.httpServer(port = 1234)),
        ).asJsonObject.getAsJsonObject("mcpServers")

        assertTrue(merged.has("something-else"))
        assertTrue(merged.has(McpClientConfig.SERVER_NAME))
    }

    @Test
    fun `merge keeps unrelated top level keys`() {
        val merged = McpClientConfig.merge(
            """{ "permissions": { "allow": ["Bash(ls:*)"] } }""",
            McpClientConfig.httpServer(port = 1234),
        )

        assertTrue(JsonParser.parseString(merged).asJsonObject.has("permissions"))
    }

    @Test
    fun `installing twice updates one entry rather than duplicating it`() {
        val first = McpClientConfig.merge(null, McpClientConfig.httpServer(port = 1111))
        val second = McpClientConfig.merge(first, McpClientConfig.httpServer(port = 2222))

        val servers = JsonParser.parseString(second).asJsonObject.getAsJsonObject("mcpServers")
        assertEquals(1, servers.size())
        assertTrue(
            servers.getAsJsonObject(McpClientConfig.SERVER_NAME).get("url").asString
                .contains("2222"),
        )
    }

    @Test
    fun `merge refuses to overwrite a file that is not json`() {
        assertThrows<JsonSyntaxException> {
            McpClientConfig.merge("{ not json at all", JsonObject())
        }
    }

    /** Valid JSON, but not a config: replacing it would discard whatever was written there. */
    @Test
    fun `merge refuses when mcpServers is not an object`() {
        assertThrows<JsonSyntaxException> {
            McpClientConfig.merge("""{ "mcpServers": "oops" }""", McpClientConfig.httpServer(1))
        }
    }

    /**
     * Deliberate, not an oversight: an empty file is strictly not valid JSON, but it configures
     * nothing and has nothing to lose, so refusing would block an install over a `touch`.
     */
    @Test
    fun `merge treats an empty file as an absent one`() {
        for (empty in listOf(null, "", "   ", "\n\t ")) {
            val merged = McpClientConfig.merge(empty, McpClientConfig.httpServer(1))

            assertTrue(McpClientConfig.contains(merged), "should have installed over: '$empty'")
        }
    }

    @Test
    fun `merge refuses when the top level is not an object`() {
        assertThrows<JsonSyntaxException> {
            McpClientConfig.merge("""["not", "a", "config"]""", McpClientConfig.httpServer(1))
        }
    }

    @Test
    fun `contains reports an existing entry`() {
        assertFalse(McpClientConfig.contains(null))
        assertFalse(McpClientConfig.contains("""{ "mcpServers": {} }"""))
        assertTrue(
            McpClientConfig.contains(McpClientConfig.merge(null, McpClientConfig.httpServer(1))),
        )
    }

    @Test
    fun `shell export values are single quoted`() {
        assertEquals("''", shellSingleQuoted(""))
        assertEquals("'plain-token'", shellSingleQuoted("plain-token"))
        assertEquals("'ab'\"'\"'cd'", shellSingleQuoted("ab'cd"))
        assertEquals("'a b\tc\nd'", shellSingleQuoted("a b\tc\nd"))
        assertEquals("'$HOME'", shellSingleQuoted("$HOME"))
    }
}
