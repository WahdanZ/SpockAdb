package spock.adb.assistant

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * The LLM API key, in `PasswordSafe` and nowhere else.
 *
 * Never in the settings XML, the audit history or the log. A `PersistentStateComponent` field
 * would put the developer's key in a plain file that syncs with their IDE settings, and the
 * plugin already writes MCP call history and `idea.log` entries that a key must never reach.
 *
 * Read on demand rather than cached: rotating a key in Settings should take effect on the next
 * request, not the next restart.
 */
object AssistantKeyStore {

    fun apiKey(provider: AssistantProvider): String =
        PasswordSafe.instance.getPassword(attributesFor(provider)).orEmpty()

    fun store(provider: AssistantProvider, key: String) {
        val attributes = attributesFor(provider)
        // A blank key clears the entry rather than storing an empty secret, so "no key" is one
        // state instead of two that behave the same but read differently.
        if (key.isBlank()) {
            PasswordSafe.instance.set(attributes, null)
        } else {
            PasswordSafe.instance.set(attributes, Credentials(provider.name, key))
        }
    }

    fun hasKey(provider: AssistantProvider): Boolean = apiKey(provider).isNotBlank()

    private fun attributesFor(provider: AssistantProvider) =
        CredentialAttributes(generateServiceName(SERVICE, provider.name), provider.name)

    private const val SERVICE = "Spock ADB assistant"
}
