package spock.adb.assistant

/**
 * Which LLM the assistant talks to.
 *
 * A domain type rather than a member of [AssistantKeyStore]: the choice decides the client, the
 * defaults and the label on the settings screen, and only incidentally which credential entry
 * is read. Free of IntelliJ types so the defaults can be tested directly.
 */
enum class AssistantProvider {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    ;

    /** Blank where there is no sensible default and the developer has to say. */
    val defaultModel: String
        get() = when (this) {
            ANTHROPIC -> AnthropicClient.DEFAULT_MODEL
            // Nothing worth guessing: an OpenAI-compatible endpoint may be OpenAI itself, a
            // local llama.cpp server or a gateway, and each names its models differently.
            OPENAI_COMPATIBLE -> ""
        }

    val defaultBaseUrl: String
        get() = when (this) {
            ANTHROPIC -> AnthropicClient.DEFAULT_BASE_URL
            OPENAI_COMPATIBLE -> ""
        }

    /** What the settings dropdown shows. */
    val label: String
        get() = when (this) {
            ANTHROPIC -> "Anthropic"
            OPENAI_COMPATIBLE -> "OpenAI-compatible"
        }

    /** True when this provider cannot work until the developer fills the field in. */
    val needsExplicitModel: Boolean get() = defaultModel.isBlank()

    val needsExplicitBaseUrl: Boolean get() = defaultBaseUrl.isBlank()

    companion object {
        /**
         * The one place a base URL is canonicalised.
         *
         * The settings screen compares what is typed against what is stored to decide whether
         * anything changed, so it has to normalise identically. When it did not, a trailing
         * slash left Settings reporting itself modified for ever — it stored one value and
         * compared another. It lives here, free of IntelliJ types, so that rule can be run
         * rather than only read.
         */
        fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

        /**
         * Reads a stored name, falling back rather than throwing.
         *
         * A settings file written by a version that knew a provider this one does not must still
         * load: losing the model and base URL because of one unknown word would be a worse
         * outcome than quietly starting from the default.
         */
        fun parse(name: String?): AssistantProvider =
            entries.firstOrNull { it.name == name } ?: ANTHROPIC
    }
}
