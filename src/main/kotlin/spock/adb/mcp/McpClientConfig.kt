package spock.adb.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Builds the JSON an MCP client needs to reach this IDE, and merges it into a client's own
 * config file.
 *
 * Kept apart from [McpServerService] — which owns sockets, settings and the IDE's config
 * directory — because everything here is a pure function of a handful of strings. That makes
 * the one part of the feature with a security consequence, namely whether a credential ends
 * up in the generated text, something a test can assert on without an IDE.
 */
object McpClientConfig {

    /** The key under `mcpServers`. One name, so re-installing replaces rather than duplicates. */
    const val SERVER_NAME = "spock-adb"

    /**
     * Where the HTTP config reads its token from instead of embedding one.
     *
     * A config that names an environment variable can be pasted into a chat, a bug report or a
     * shared dotfile; a config with the token in it is a credential, and the two are
     * indistinguishable once they are both just JSON on a clipboard. Claude Code expands
     * `${VAR}` in `.mcp.json`; clients that do not are served by the literal form, which is
     * behind an explicit confirmation.
     */
    const val TOKEN_ENV_VAR = "SPOCK_ADB_MCP_TOKEN"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * The HTTP server entry.
     *
     * [token] null is the safe default: the header references [TOKEN_ENV_VAR] and the secret
     * stays in the developer's environment. Passing a token writes it into the text verbatim,
     * which is only ever right for a client that cannot expand environment variables — and
     * should be confirmed by a human first.
     */
    fun httpServer(port: Int, token: String? = null): JsonObject = JsonObject().apply {
        addProperty("type", "http")
        addProperty("url", "http://127.0.0.1:$port${McpHttpServer.ENDPOINT}")
        add(
            "headers",
            JsonObject().apply {
                addProperty("Authorization", "Bearer ${token ?: "\${$TOKEN_ENV_VAR}"}")
            },
        )
    }

    /**
     * The stdio server entry, which carries no credential at all.
     *
     * The client is pointed at the launcher and at the endpoint descriptor; the token lives in
     * that `600` file and is read by the launcher, not by whoever holds this JSON.
     *
     * Built with Gson rather than string interpolation because these are filesystem paths: a
     * Windows path in a hand-rolled JSON string would produce `C:\Users`, which is invalid JSON
     * and would be silently mangled where it is not.
     */
    fun stdioServer(
        javaExecutable: String,
        classpath: String,
        launcherClass: String,
        descriptor: String,
    ): JsonObject = JsonObject().apply {
        addProperty("command", javaExecutable)
        add(
            "args",
            JsonArray().apply {
                add("-cp")
                add(classpath)
                add(launcherClass)
                add(descriptor)
            },
        )
    }

    /** One server entry wrapped in the `mcpServers` envelope every client expects. */
    fun document(server: JsonObject): String = gson.toJson(
        JsonObject().apply {
            add("mcpServers", JsonObject().apply { add(SERVER_NAME, server) })
        },
    )

    /**
     * Puts [server] into an existing client config, leaving everything else alone.
     *
     * A developer's `.mcp.json` is theirs: it may hold half a dozen other servers, and an
     * install that overwrote the file would silently disconnect all of them. Only the
     * [SERVER_NAME] key is written, so installing twice updates one entry — which is what makes
     * re-installing after a port change or a rotation safe.
     *
     * A file that holds something and is not JSON — or whose `mcpServers` is not an object — is
     * an error rather than something to overwrite: the alternative is destroying a config the
     * developer hand-wrote and mistyped.
     *
     * An **empty or whitespace-only** file is treated as an absent one, deliberately. It is
     * strictly not valid JSON, but it configures nothing and holds nothing to lose — refusing
     * would block an install over a file someone had merely `touch`ed.
     */
    fun merge(existing: String?, server: JsonObject): String {
        val root = parseRoot(existing)
        serversIn(root).add(SERVER_NAME, server)
        return gson.toJson(root)
    }

    private fun parseRoot(existing: String?): JsonObject {
        if (existing.isNullOrBlank()) return JsonObject()

        val parsed = runCatching { JsonParser.parseString(existing) }
            .getOrElse { throw JsonSyntaxException("not valid JSON", it) }
        return parsed as? JsonObject
            ?: throw JsonSyntaxException("the top level is not a JSON object")
    }

    /**
     * The `mcpServers` object to write into.
     *
     * Absent is the ordinary case for a project that has never configured a server. Present but
     * not an object is a file someone mistyped, and replacing it with a fresh one would quietly
     * discard whatever they had written there — so it is refused, by name, rather than through a
     * ClassCastException with nothing in it to explain the file it came from.
     */
    private fun serversIn(root: JsonObject): JsonObject =
        when (val servers = root.get("mcpServers")) {
            null -> JsonObject().also { root.add("mcpServers", it) }
            is JsonObject -> servers
            else -> throw JsonSyntaxException("\"mcpServers\" is not a JSON object")
        }

    /** Whether [existing] already configures this server, so an install can say what it did. */
    fun contains(existing: String?): Boolean {
        if (existing.isNullOrBlank()) return false
        val root = runCatching { JsonParser.parseString(existing) }.getOrNull() as? JsonObject
            ?: return false
        return (root.get("mcpServers") as? JsonObject)?.has(SERVER_NAME) == true
    }
}

// --------------------------------------------------------------------------------------
// The server's own configurations.
//
// Extensions rather than members: assembling a document out of an entry is a property of the
// format, not of the thing that owns the sockets, and [McpServerService] is already at the
// size the project's own static analysis complains about.
// --------------------------------------------------------------------------------------

/** The HTTP configuration as a whole document. See [McpServerService.httpServerEntry]. */
fun McpServerService.clientConfiguration(includeToken: Boolean = false): String =
    McpClientConfig.document(httpServerEntry(includeToken))

/** The stdio configuration as a whole document. It carries no credential. */
fun McpServerService.stdioClientConfiguration(): String =
    McpClientConfig.document(stdioServerEntry())

/**
 * The entry to install into a client's config file, preferring the one with no credential.
 *
 * stdio when the bridge bound, which is the normal case; HTTP otherwise, and then in its
 * env-var form. Either way a `.mcp.json` written from here is safe to share as far as secrets
 * go — it names a descriptor file or an environment variable, never a token.
 */
fun McpServerService.preferredServerEntry(): JsonObject =
    if (prefersStdio) stdioServerEntry() else httpServerEntry(includeToken = false)

/**
 * The shell line that gives the HTTP configuration its token.
 *
 * The env-var form is only usable if the developer can get the value into their client's
 * environment, and the honest moment to hand the secret over is when they have just rotated
 * it — not on every copy of the config.
 */
fun McpServerService.tokenExportLine(): String =
    "export ${McpClientConfig.TOKEN_ENV_VAR}=$token"
