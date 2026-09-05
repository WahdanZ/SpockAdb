package spock.adb.assistant

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import spock.adb.mcp.McpServerService
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer

/**
 * The in-IDE assistant: ask a question about the connected device and let the model use the
 * plugin's own tools to answer it.
 *
 * The same registry, gate, confirmation dialog and audit trail as the MCP transports — see
 * [AssistantService.newLoop]. Nothing here can do anything an external agent could not; the
 * difference is that the developer does not have to configure a client to get it.
 *
 * **Everything typed here, and every tool result, leaves the machine for the configured
 * provider** — including screenshots, logcat and package lists. That is stated in the panel
 * rather than only in the docs, because it is the one thing a developer cannot undo after the
 * fact.
 */
class AssistantPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val settings = AssistantService.getInstance()
    private val transcript = AssistantTranscript()

    private val transcriptArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
    }
    private val inputArea = JBTextArea(INPUT_ROWS, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask about the connected device.  Ctrl+Enter to send."
    }

    private val sendButton = JButton("Send", AllIcons.Actions.Execute)
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend)
    private val attachContext = JBCheckBox("Attach debugging context", settings.attachContext)
    private val statusLabel = JBLabel(" ")

    /**
     * Deltas arrive on the pooled thread running the loop and are drained onto the EDT every
     * [FLUSH_INTERVAL_MS], following the Logcat panel: a model streaming token by token would
     * otherwise post hundreds of `invokeLater`s a second and make the IDE feel stuck.
     */
    private val incoming = ConcurrentLinkedQueue<String>()
    private val flushTimer = Timer(FLUSH_INTERVAL_MS) { drainIncoming() }

    /** Polled by the loop between events, so a stopped turn ends at a message boundary. */
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var running = false

    /** Set on dispose, so a turn still in flight cannot update a dead panel. */
    @Volatile
    private var disposed = false

    /** Kept across turns so the model can refer back to what it already found. */
    private val conversation = mutableListOf<LlmMessage>()

    init {
        setToolbar(header())
        setContent(body())
        wire()
        flushTimer.isRepeats = true
        flushTimer.start()
        refreshState()
    }

    // ------------------------------------------------------------------ layout

    private fun header(): JComponent {
        val controls = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(GAP)
            add(sendButton)
            add(stopButton)
            add(attachContext)
            add(JButton("Clear").apply { addActionListener { clearConversation() } })
            add(JButton("Copy Transcript").apply { addActionListener { copyTranscript() } })
            add(JButton("Settings").apply { addActionListener { openSettings() } })
        }

        return JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, GAP, GAP, GAP)
                    add(statusLabel, BorderLayout.WEST)
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun body(): JComponent = JPanel(BorderLayout()).apply {
        add(JBScrollPane(transcriptArea), BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(GAP)
                add(JBScrollPane(inputArea), BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
    }

    private fun wire() {
        sendButton.addActionListener { send() }
        stopButton.addActionListener { stop() }
        attachContext.addActionListener { settings.attachContext = attachContext.isSelected }

        // Ctrl+Enter rather than Enter: the input is a text area, and a multi-line question
        // about a stack trace is the normal case, not the exception.
        bind(inputArea, KeyEvent.VK_ENTER, CTRL_MASK, "spock.assistant.send") { send() }
        bind(inputArea, KeyEvent.VK_ESCAPE, 0, "spock.assistant.stop") { stop() }
        bind(transcriptArea, KeyEvent.VK_ESCAPE, 0, "spock.assistant.stop") { stop() }

        // The key is added in a dialog this panel does not own, and nothing tells it when that
        // happened. Without this the panel stays disabled after the developer configures it,
        // which reads as the feature being broken rather than as a stale screen.
        addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentShown(event: java.awt.event.ComponentEvent) = refreshState()
            },
        )
    }

    private fun bind(component: JComponent, keyCode: Int, modifiers: Int, name: String, action: () -> Unit) {
        component.inputMap.put(KeyStroke.getKeyStroke(keyCode, modifiers), name)
        component.actionMap.put(
            name,
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent) = action()
            },
        )
    }

    // ------------------------------------------------------------------ sending

    private fun send() {
        if (running) return
        val question = inputArea.text.orEmpty().trim()
        if (question.isEmpty()) return

        if (!settings.isConfigured) {
            note(AssistantTranscript.Kind.ERROR, NO_KEY)
            return
        }

        inputArea.text = ""
        append(AssistantTranscript.Kind.USER, question)
        beginTurn()

        val attach = attachContext.isSelected
        ApplicationManager.getApplication().executeOnPooledThread { runTurn(question, attach) }
    }

    /**
     * One turn, on a pooled thread.
     *
     * Everything that can throw is inside: an exception escaping here would reach the IDE's
     * pooled-thread handler as a stack trace in the log, and the panel would sit on "Thinking…"
     * for ever with no way back.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runTurn(question: String, attach: Boolean) {
        val outcome = try {
            val context = McpServerService.getInstance().toolContext
            val loop = settings.newLoop { context }
            val message = if (attach && conversation.isEmpty()) withDebugContext(question, context) else question
            loop.run(
                system = AssistantPrompt.SYSTEM,
                userMessage = message,
                conversation = conversation,
                onTextDelta = { incoming += it },
                isCancelled = cancelled::get,
            )
        } catch (e: Exception) {
            AgentOutcome.Failed(e.message ?: "${e.javaClass.simpleName} while answering.")
        }
        onEdt { endTurn(outcome) }
    }

    /**
     * Prepends one `android_get_debug_context` result to the first question of a conversation.
     *
     * The triage bundle in one call rather than four the model has to know to make: it starts
     * with the activity, the UI semantics and recent logcat already in hand, which is most of
     * what "why is this screen wrong" needs. Only the first message — repeating it every turn
     * would resend a stale snapshot and pay for it each time.
     *
     * A failure here is a note, not a stop: the question is still worth asking without it.
     */
    private fun withDebugContext(question: String, context: spock.adb.mcp.tools.ToolContext): String {
        val tool = spock.adb.mcp.tools.ToolRegistry.find(DEBUG_CONTEXT_TOOL)
            ?: return question
        if (!McpServerService.getInstance().isToolEnabled(DEBUG_CONTEXT_TOOL)) {
            onEdt {
                note(
                    AssistantTranscript.Kind.NOTE,
                    "$DEBUG_CONTEXT_TOOL is switched off, so no context was attached.",
                )
            }
            return question
        }

        val result = runCatching { tool.execute(com.google.gson.JsonObject(), context) }.getOrNull()
        val text = result?.content
            ?.filterIsInstance<spock.adb.mcp.tools.ToolContent.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()

        if (text.isBlank()) {
            onEdt { note(AssistantTranscript.Kind.NOTE, "Could not read the device context; asking without it.") }
            return question
        }

        onEdt { note(AssistantTranscript.Kind.NOTE, "Attached the current device context.") }
        return "Current device context:\n\n$text\n\n---\n\n$question"
    }

    private fun beginTurn() {
        running = true
        cancelled.set(false)
        refreshState()
        statusLabel.text = "◌ Thinking…"
        statusLabel.foreground = JBColor.GRAY
    }

    /**
     * Closes the turn, naming what actually happened.
     *
     * Each outcome reads differently to a developer: a refusal is not a failure, a hit
     * iteration cap is not an answer, and a cancelled turn keeps whatever had streamed.
     */
    private fun endTurn(outcome: AgentOutcome) {
        drainIncoming()
        when (outcome) {
            is AgentOutcome.Answered -> closeStreamed(outcome.text)

            is AgentOutcome.Cancelled -> {
                closeStreamed(outcome.text)
                note(AssistantTranscript.Kind.NOTE, "Stopped.")
            }

            is AgentOutcome.ReachedIterationCap -> {
                closeStreamed(outcome.text)
                note(
                    AssistantTranscript.Kind.NOTE,
                    "Stopped after ${outcome.cap} steps without a final answer. Ask something " +
                        "narrower, or drive the tools yourself from the MCP panel.",
                )
            }

            is AgentOutcome.Refused -> {
                closeStreamed(outcome.text)
                note(AssistantTranscript.Kind.NOTE, "The provider declined this request.")
            }

            is AgentOutcome.Failed -> note(AssistantTranscript.Kind.ERROR, outcome.message)
        }

        running = false
        statusLabel.text = " "
        refreshState()
    }

    /**
     * Records the streamed answer in the transcript model.
     *
     * The text is already on screen — it arrived through [drainIncoming] — so this adds it to
     * the model without re-rendering, which would make the answer appear twice.
     */
    private fun closeStreamed(text: String) {
        if (text.isBlank()) return
        transcript.add(AssistantTranscript.Kind.ASSISTANT, text)
        transcriptArea.append("\n\n")
    }

    private fun stop() {
        if (!running) return
        cancelled.set(true)
        statusLabel.text = "◌ Stopping at the next step…"
    }

    // ------------------------------------------------------------------ transcript

    /** Drains on the EDT. See [incoming]. */
    private fun drainIncoming() {
        if (incoming.isEmpty()) return
        val batch = buildString {
            while (true) {
                append(incoming.poll() ?: break)
            }
        }
        transcriptArea.append(batch)
        transcriptArea.caretPosition = transcriptArea.document.length
    }

    private fun append(kind: AssistantTranscript.Kind, text: String) {
        transcript.add(kind, text)
        if (transcriptArea.document.length > 0) transcriptArea.append("\n\n")
        transcriptArea.append(AssistantTranscript.render(AssistantTranscript.Entry(kind, text)))
        transcriptArea.append("\n\n")
        transcriptArea.caretPosition = transcriptArea.document.length
    }

    private fun note(kind: AssistantTranscript.Kind, text: String) = append(kind, text)

    private fun clearConversation() {
        conversation.clear()
        transcript.clear()
        transcriptArea.text = ""
        incoming.clear()
        refreshState()
    }

    private fun copyTranscript() {
        if (transcript.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(transcript.render()))
        statusLabel.text = "Transcript copied."
        statusLabel.foreground = JBColor.GRAY
    }

    // ------------------------------------------------------------------ state

    private fun refreshState() {
        val configured = settings.isConfigured
        sendButton.isEnabled = configured && !running
        stopButton.isEnabled = running
        inputArea.isEnabled = configured

        if (transcript.isEmpty()) {
            transcriptArea.text = if (configured) "" else NO_KEY
        }
    }

    /** Back to the EDT, dropping the update when the panel or the project has gone. */
    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ block() }) { disposed || project.isDisposed }

    /** Blocks until the dialog closes, so the state can be re-read the moment it returns. */
    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_DISPLAY_NAME)
        refreshState()
    }

    override fun dispose() {
        disposed = true
        // A turn in flight is stopped rather than abandoned: the loop polls this between
        // steps, so closing the tool window does not leave a model call running against a
        // device the developer has stopped looking at.
        cancelled.set(true)
        flushTimer.stop()
        incoming.clear()
    }

    private companion object {
        const val GAP = 4
        const val INPUT_ROWS = 4
        const val FLUSH_INTERVAL_MS = 100
        const val CTRL_MASK = java.awt.event.InputEvent.CTRL_DOWN_MASK
        const val SETTINGS_DISPLAY_NAME = "Spock ADB"
        const val DEBUG_CONTEXT_TOOL = "android_get_debug_context"

        const val NO_KEY =
            "No API key is configured.\n\n" +
                "Open Settings > Tools > Spock ADB and add one for your provider.\n\n" +
                "Note that everything you send here — your questions and every tool result, " +
                "including screenshots, logcat and package lists — leaves this machine for the " +
                "provider you configure."
    }
}
