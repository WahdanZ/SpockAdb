package spock.adb.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssistantProviderTest {

    @Test
    fun `an unknown stored provider falls back rather than throwing`() {
        // A settings file written by a version that knew a provider this one does not must
        // still load: losing the model and base URL over one unknown word would be worse than
        // quietly starting from the default.
        assertEquals(AssistantProvider.ANTHROPIC, AssistantProvider.parse("SOMETHING_NEW"))
        assertEquals(AssistantProvider.ANTHROPIC, AssistantProvider.parse(null))
        assertEquals(AssistantProvider.ANTHROPIC, AssistantProvider.parse(""))
    }

    @Test
    fun `a known provider round-trips through its stored name`() {
        AssistantProvider.entries.forEach {
            assertEquals(it, AssistantProvider.parse(it.name))
        }
    }

    @Test
    fun `Anthropic needs neither a model nor a base URL from the developer`() {
        assertFalse(AssistantProvider.ANTHROPIC.needsExplicitModel)
        assertFalse(AssistantProvider.ANTHROPIC.needsExplicitBaseUrl)
        assertTrue(AssistantProvider.ANTHROPIC.defaultModel.isNotBlank())
        assertTrue(AssistantProvider.ANTHROPIC.defaultBaseUrl.startsWith("https://"))
    }

    @Test
    fun `an OpenAI-compatible endpoint has to be told both`() {
        // There is nothing to guess: it may be OpenAI itself, a local llama.cpp server or a
        // gateway, and each names its models and its host differently.
        assertTrue(AssistantProvider.OPENAI_COMPATIBLE.needsExplicitModel)
        assertTrue(AssistantProvider.OPENAI_COMPATIBLE.needsExplicitBaseUrl)
    }

    @Test
    fun `a base URL is canonicalised the same way wherever it is compared`() {
        // The defect: Settings compared the raw field against the stored value, which had its
        // trailing slash dropped on the way in — so a URL ending in "/" left the screen
        // reporting itself modified for ever, and OK never went quiet.
        assertEquals("https://api.example.com", AssistantProvider.normalizeBaseUrl("https://api.example.com/"))
        assertEquals("https://api.example.com", AssistantProvider.normalizeBaseUrl("  https://api.example.com  "))
        assertEquals("https://api.example.com", AssistantProvider.normalizeBaseUrl("https://api.example.com///"))
        assertEquals("https://api.example.com", AssistantProvider.normalizeBaseUrl("https://api.example.com"))
        assertEquals("", AssistantProvider.normalizeBaseUrl("   "))
    }

    @Test
    fun `normalising is idempotent, so storing a stored value changes nothing`() {
        val once = AssistantProvider.normalizeBaseUrl("https://api.example.com/v1/")
        assertEquals(once, AssistantProvider.normalizeBaseUrl(once))
    }

    @Test
    fun `every provider has a label for the settings dropdown`() {
        AssistantProvider.entries.forEach {
            assertTrue(it.label.isNotBlank(), "${it.name} needs a label")
        }
    }
}
