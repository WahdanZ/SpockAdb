package spock.adb.mcp

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * The MCP session token: in `PasswordSafe`, and nowhere else.
 *
 * It used to live in `spock-adb-mcp.xml` as a plain attribute, which put a credential for the
 * developer's device *and* filesystem into a file that IDE settings sync copies between
 * machines, that backup tools pick up, and that anything running as the developer can read
 * without the OS asking anyone anything. The LLM API key next door has always been kept in the
 * keychain — the more dangerous of the two secrets was the one in the clear.
 *
 * Unlike [spock.adb.assistant.AssistantKeyStore], which reads on demand, this caches: the token
 * is compared on every connection that reaches either transport, and a keychain read per
 * connection would be slow and, on some platforms, a prompt.
 */
object McpTokenStore {

    private val log = Logger.getInstance(McpTokenStore::class.java)

    /** Null means "not looked up yet" rather than "no token". */
    private val cached = AtomicReference<String?>(null)

    /**
     * The token, minted on first use.
     *
     * **Never call this on the EDT** the first time in an IDE session: `PasswordSafe` reads the
     * OS keychain, which blocks and trips `SlowOperations`. Afterwards it is a field read.
     *
     * A keychain that cannot be read or written leaves the token in memory for this session
     * rather than falling back to the settings file: a server that works until the next restart
     * is a better answer than one that quietly writes a credential back into a plain file.
     */
    @Synchronized
    fun current(): String {
        cached.get()?.let { return it }

        val stored = runCatching { PasswordSafe.instance.getPassword(ATTRIBUTES).orEmpty() }
            .onFailure { log.warn("Could not read the MCP session token from PasswordSafe", it) }
            .getOrDefault("")

        val token = stored.ifBlank { generate().also { store(it) } }
        cached.set(token)
        return token
    }

    /**
     * Takes over a token an earlier version wrote into the settings file.
     *
     * Returns whether the keychain accepted it, because the caller may only clear the plain-text
     * copy once something else is holding it. Regenerating instead would leave the old secret in
     * the file it was supposed to be got out of, and would break every HTTP client the developer
     * has already configured.
     */
    @Synchronized
    fun adopt(legacy: String): Boolean {
        if (legacy.isBlank()) return false
        if (!store(legacy)) return false

        cached.set(legacy)
        log.info("Moved the MCP session token out of the settings file and into PasswordSafe")
        return true
    }

    /**
     * A new token, replacing whatever is stored.
     *
     * Throws if the keychain refuses, having changed nothing: the old token keeps working, which
     * is a better failure than a rotation that half happened and leaves clients authenticating
     * against a value nothing kept.
     */
    @Synchronized
    fun rotate(): String {
        val fresh = generate()
        PasswordSafe.instance.set(ATTRIBUTES, Credentials(KEY, fresh))
        cached.set(fresh)
        return fresh
    }

    /** 32 bytes of [SecureRandom], URL-safe so it survives a header and a properties file. */
    private fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun store(token: String): Boolean =
        runCatching { PasswordSafe.instance.set(ATTRIBUTES, Credentials(KEY, token)) }
            .onFailure { log.warn("Could not store the MCP session token in PasswordSafe", it) }
            .isSuccess

    private const val TOKEN_BYTES = 32

    private const val SERVICE = "Spock ADB MCP"

    /** One token per IDE installation — the same scope the settings attribute had. */
    private const val KEY = "session-token"

    private val ATTRIBUTES = CredentialAttributes(generateServiceName(SERVICE, KEY), KEY)
}
