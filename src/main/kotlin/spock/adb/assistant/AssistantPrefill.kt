package spock.adb.assistant

/**
 * How another tab hands a prepared question to the Assistant.
 *
 * Deliberately one method taking a string. The alternative — letting Logcat reach into the
 * Assistant's text area — would make two panels share a Swing field, so a change to either
 * layout would break the other, and it would make it far too easy to add a "send it too" line
 * later. The contract is that the prompt is *placed*, never sent: the developer sees exactly
 * what is about to leave the machine and presses Send themselves.
 *
 * Called on the EDT.
 */
fun interface AssistantPrefill {

    fun prefill(prompt: String)
}
