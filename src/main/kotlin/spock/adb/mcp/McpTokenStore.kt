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
     * The token, resolved once per IDE session: from the keychain, from a token an earlier
     * version left in the settings file, or freshly minted.
     *
     * **Resolution and migration are the same operation, deliberately.** They used to be two —
     * a startup task that adopted the legacy token and this, which minted one — and whichever
     * ran second lost: a server started before the adoption task captured a newly minted token,
     * then adoption replaced the cache with the legacy one, so `token` and the generated config
     * disagreed with the servers actually running. Doing both inside one synchronized call
     * means the first caller decides and every later caller sees that decision.
     *
     * **Never call this on the EDT** the first time in an IDE session: `PasswordSafe` reads the
     * OS keychain, which blocks and trips `SlowOperations`. Afterwards it is a field read.
     *
     * @param legacy the token an earlier version wrote into the settings file, if any.
     * @param onAdopted run when [legacy] has been safely written to the keychain — and only
     *  then, since it is what clears the plain-text copy.
     */
    @Synchronized
    fun current(legacy: () -> String = { "" }, onAdopted: () -> Unit = {}): String {
        cached.get()?.let { return it }

        val stored = runCatching { PasswordSafe.instance.getPassword(ATTRIBUTES).orEmpty() }
            .onFailure { log.warn("Could not read the MCP session token from PasswordSafe", it) }
            .getOrElse {
                throw IllegalStateException("Could not read the MCP session token from PasswordSafe", it)
            }
        if (stored.isNotBlank()) return stored.also { cached.set(it) }

        val inherited = runCatching(legacy)
            .onFailure { log.warn("Could not read the legacy MCP session token from settings", it) }
            .getOrElse {
                throw IllegalStateException("Could not read the legacy MCP session token from settings", it)
            }
        if (inherited.isNotBlank()) return adopt(inherited, onAdopted)

        // A keychain that will not store leaves the token in memory for this session rather
        // than falling back to the settings file. If it cannot be persisted, fail here rather
        // than handing out an in-memory-only credential that every restart would invalidate.
        val fresh = generate()
        if (!store(fresh)) {
            throw IllegalStateException("Could not store the MCP session token in PasswordSafe")
        }
        cached.set(fresh)
        return fresh
    }

    /**
     * Takes over a token an earlier version wrote into the settings file.
     *
     * The value is cached **whether or not the keychain accepts it**: clients already hold this
     * token, and minting a new one because a keychain write failed would break every one of them
     * for the rest of the session. A failed write only means [onAdopted] does not run, so the
     * plain-text copy stays and the migration is retried on the next startup.
     */
    private fun adopt(legacy: String, onAdopted: () -> Unit): String {
        cached.set(legacy)
        if (store(legacy)) {
            log.info("Moved the MCP session token out of the settings file and into PasswordSafe")
            runCatching(onAdopted)
                .onFailure { log.warn("Could not clear the migrated MCP token from settings", it) }
        }
        return legacy
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
        if (!store(fresh)) {
            throw IllegalStateException("Could not store the rotated MCP session token in PasswordSafe")
        }
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
        runCatching {
            PasswordSafe.instance.set(ATTRIBUTES, Credentials(KEY, token))
            PasswordSafe.instance.getPassword(ATTRIBUTES) == token
        }
            .onFailure { log.warn("Could not store the MCP session token in PasswordSafe", it) }
            .getOrDefault(false)
            .also {
                if (!it) log.warn("PasswordSafe did not persist the MCP session token after write")
            }

    private const val TOKEN_BYTES = 32

    private const val SERVICE = "Spock ADB MCP"

    /** One token per IDE installation — the same scope the settings attribute had. */
    private const val KEY = "session-token"

    private val ATTRIBUTES = CredentialAttributes(generateServiceName(SERVICE, KEY), KEY)
}
