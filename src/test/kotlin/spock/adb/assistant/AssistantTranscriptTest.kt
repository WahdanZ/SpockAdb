package spock.adb.assistant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The transcript is where a leak would show first: an agent session appends a line per tool
 * call, and 25 iterations of several calls each adds up faster than a chat does.
 */
class AssistantTranscriptTest {

    @Test
    fun `entries come back in the order they were added`() {
        val transcript = AssistantTranscript()
        transcript.add(AssistantTranscript.Kind.USER, "why is the button dead")
        transcript.add(AssistantTranscript.Kind.TOOL, "android_get_ui_tree")
        transcript.add(AssistantTranscript.Kind.ASSISTANT, "it is disabled")

        assertEquals(
            listOf("why is the button dead", "android_get_ui_tree", "it is disabled"),
            transcript.all().map { it.text },
        )
    }

    @Test
    fun `the oldest entries are dropped once the cap is reached`() {
        val transcript = AssistantTranscript(capacity = 3)

        (1..10).forEach { transcript.add(AssistantTranscript.Kind.TOOL, "call $it") }

        assertEquals(listOf("call 8", "call 9", "call 10"), transcript.all().map { it.text })
    }

    @Test
    fun `clearing empties it`() {
        val transcript = AssistantTranscript()
        transcript.add(AssistantTranscript.Kind.USER, "hello")
        assertFalse(transcript.isEmpty())

        transcript.clear()

        assertTrue(transcript.isEmpty())
        assertEquals("", transcript.render())
    }

    @Test
    fun `rendering marks who said what`() {
        // The prefixes are what survives Copy Transcript into a bug report, where colours and
        // fonts do not — so a rendered transcript has to be readable as plain text.
        val transcript = AssistantTranscript()
        transcript.add(AssistantTranscript.Kind.USER, "what activity is on screen")
        transcript.add(AssistantTranscript.Kind.ASSISTANT, "MainActivity")

        val rendered = transcript.render()

        assertTrue(rendered.startsWith("You:  what activity is on screen"), rendered)
        assertTrue(rendered.contains("MainActivity"), rendered)
    }

    @Test
    fun `an error is marked differently from a note`() {
        // A developer scanning the transcript has to be able to tell "this failed" from "here
        // is something you should know" without reading either in full.
        assertTrue(
            AssistantTranscript.prefix(AssistantTranscript.Kind.ERROR) !=
                AssistantTranscript.prefix(AssistantTranscript.Kind.NOTE),
        )
    }

    @Test
    fun `an assistant answer carries no prefix`() {
        // The answer is the thing being read; prefixing every line of it would be noise.
        assertEquals("", AssistantTranscript.prefix(AssistantTranscript.Kind.ASSISTANT))
    }
}
