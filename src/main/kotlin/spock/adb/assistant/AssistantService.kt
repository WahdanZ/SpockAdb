package spock.adb.assistant

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import spock.adb.mcp.McpServerService

/**
 * The assistant's configuration, and the one place a configured [AgentLoop] is built.
 *
 * Application-level like [McpServerService], and for the same reason: the key, the provider
 * and the model belong to the developer rather than to a project, and a per-project assistant
 * would ask for the key again every time a second project was opened.
 *
 * **No key lives here.** [AssistantKeyStore] holds it in `PasswordSafe`; this holds only the
 * settings that are safe to write to `spock-adb-assistant.xml`.
 */
@Service(Service.Level.APP)
@State(name = "SpockAdbAssistant", storages = [Storage("spock-adb-assistant.xml")])
class AssistantService : PersistentStateComponent<AssistantSettings> {

    private var settings = AssistantSettings()

    override fun getState(): AssistantSettings = settings

    override fun loadState(state: AssistantSettings) {
        settings = state
    }

    var provider: AssistantProvider
        get() = AssistantProvider.parse(settings.provider)
        set(value) {
            settings.provider = value.name
        }

    /**
     * The model, or the provider's default when unset.
     *
     * Blank rather than pre-filled in the settings file, so a developer who never touches the
     * field follows the default as it moves rather than being pinned to whatever it was when
     * they first opened Settings.
     */
    var model: String
        get() = settings.model.ifBlank { provider.defaultModel }
        set(value) {
            settings.model = value.trim()
        }

    var baseUrl: String
        get() = settings.baseUrl.ifBlank { provider.defaultBaseUrl }
        set(value) {
            settings.baseUrl = AssistantProvider.normalizeBaseUrl(value)
        }

    /**
     * What the developer actually typed, blank when they are following the provider's default.
     *
     * The Settings screen needs this rather than [model] and [baseUrl]: showing the resolved
     * default in the field would store it on the next OK and pin them to today's default for
     * ever, so the field has to stay empty to keep meaning "follow the default".
     */
    val storedModel: String get() = settings.model

    val storedBaseUrl: String get() = settings.baseUrl

    /** What the developer last chose for "attach debugging context", remembered per IDE. */
    var attachContext: Boolean
        get() = settings.attachContext
        set(value) {
            settings.attachContext = value
        }

    /** True once a key exists for the selected provider — what the panel's empty state asks. */
    val isConfigured: Boolean get() = AssistantKeyStore.hasKey(provider)

    /**
     * The tools a conversation may use: the same registry, gate and audit trail the MCP
     * transports use.
     *
     * Handed out separately from [newLoop] so the caller can invoke a tool itself — attaching
     * device context before the first question — and have that call gated, audited and reported
     * exactly like one the model asked for. Reaching into `ToolRegistry` directly would be a
     * second path with none of that.
     */
    fun newTools(contextProvider: () -> spock.adb.mcp.tools.ToolContext): AgentTools {
        val mcp = McpServerService.getInstance()
        return RegistryAgentTools(
            contextProvider = contextProvider,
            audit = mcp::record,
            isToolEnabled = mcp::isToolEnabled,
        )
    }

    /**
     * A loop over [tools].
     *
     * Built per conversation rather than cached: the provider, model, base URL and key can all
     * change in Settings between one question and the next, and a cached client would keep
     * using the old one until the IDE restarted.
     */
    fun newLoop(tools: AgentTools): AgentLoop = AgentLoop(client = newClient(), tools = tools)

    private fun newClient(): LlmClient {
        val selected = provider
        val key = { AssistantKeyStore.apiKey(selected) }
        return when (selected) {
            AssistantProvider.ANTHROPIC ->
                AnthropicClient(apiKey = key, model = model, baseUrl = baseUrl)

            AssistantProvider.OPENAI_COMPATIBLE ->
                OpenAiCompatibleClient(apiKey = key, model = model, baseUrl = baseUrl)
        }
    }

    companion object {
        fun getInstance(): AssistantService =
            ApplicationManager.getApplication().getService(AssistantService::class.java)
    }
}

/**
 * Persisted assistant settings.
 *
 * The provider is stored as its name rather than as the enum so that a settings file written
 * by a version that knew a provider this one does not can still load — an unknown name falls
 * back to the default instead of failing deserialization.
 */
data class AssistantSettings(
    var provider: String = AssistantProvider.ANTHROPIC.name,
    /** Blank means "follow the provider's default". */
    var model: String = "",
    /** Blank means "follow the provider's default". */
    var baseUrl: String = "",
    var attachContext: Boolean = true,
)
