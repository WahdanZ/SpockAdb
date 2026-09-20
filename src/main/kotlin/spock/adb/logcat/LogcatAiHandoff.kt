package spock.adb.logcat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import spock.adb.assistant.AssistantPrefill

/**
 * The two ways a screenful of logs reaches a model, and what is said about it afterwards.
 *
 * Its own class because it is a self-contained decision — what to send, and whether to hand it
 * over or copy it — that needs only what the panel can supply through these three lambdas.
 * Keeping it out of the panel also keeps the panel's surface honest about how much of it is
 * about showing logs.
 *
 * Both entry points are hidden while [spock.adb.assistant.AssistantFeature.LOGCAT_HANDOFF_VISIBLE]
 * is off; this class stays compiled and tested either way.
 */
internal class LogcatAiHandoff(
    private val request: () -> LogcatAiRequest,
    private val assistant: () -> AssistantPrefill?,
    private val report: (String) -> Unit,
) {

    fun actions(): List<AnAction> = listOf(
        action("Ask Spock Assistant", "Open the Assistant with these logs prefilled") { ask() },
        action("Copy for AI", "Copy the same prepared context to the clipboard") { copy() },
    )

    /**
     * Hands the context over. Never sends it: the developer reads it and presses Send.
     */
    private fun ask() {
        val target = assistant() ?: run {
            report("The Assistant tab is not available.")
            return
        }
        val context = LogcatAiContextBuilder.build(request())
        if (context.lineCount == 0) {
            report("Nothing to send — no lines match the current filter.")
            return
        }
        target.prefill(context.asPrompt())
        report("${context.summary()} Opened in the Assistant — review it, then press Send.")
    }

    private fun copy() {
        val context = LogcatAiContextBuilder.build(request())
        if (context.lineCount == 0) {
            report("Nothing to copy — no lines match the current filter.")
            return
        }
        LogcatClipboard.copy(context.text)
        report("${context.summary()} Copied.")
    }

    private fun action(text: String, description: String, run: () -> Unit) =
        object : AnAction(text, description, null) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(event: AnActionEvent) = run()
        }
}
