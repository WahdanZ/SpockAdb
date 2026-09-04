package spock.adb.commandcenter

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Run ADB shell commands against the selected device, with the affordances a terminal
 * gives you and the tool window previously did not: history, favourites, a searchable
 * output pane, a real timeout, and a Cancel button that actually stops the command.
 */
class CommandCenterPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val history = CommandHistory()
    private val runner = CommandRunner()

    private val commandField = JBTextField()
    private val historyCombo = JComboBox<String>()
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        // An empty pane gives no hint that anything works; say what to do instead.
        text = EMPTY_OUTPUT_HINT
    }
    private val runButton = JButton("Run")
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val favouriteButton = JButton("☆ Favourite")
    private val statusLabel = JBLabel(" ")
    private val dangerLabel = JBLabel(" ")
    private val searchField = JBTextField(SEARCH_COLUMNS)

    private var device: ConnectedDevice? = null
    private val outputBuffer = StringBuilder()

    init {
        setToolbar(buildToolbar())
        setContent(buildContent())
        wire()
        updateStatus()
    }

    fun setDevice(connected: ConnectedDevice?) {
        device = connected
        updateStatus()
    }

    // ---------------------------------------------------------------- layout

    private fun buildContent(): JComponent {
        val input = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            border = JBUI.Borders.empty(GAP, GAP, 0, GAP)
            add(JBLabel("adb shell"), BorderLayout.WEST)
            add(commandField, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
                    add(runButton)
                    add(cancelButton)
                    add(favouriteButton)
                },
                BorderLayout.EAST,
            )
        }

        val selectors = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(0, GAP, 2, GAP)
            add(JBLabel("History:"))
            add(historyCombo)
            add(JBLabel("Find:"))
            add(searchField)
        }

        return JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    add(input, BorderLayout.NORTH)
                    add(selectors, BorderLayout.SOUTH)
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(output), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(STATUS_PAD_V, STATUS_PAD_H)
                    add(dangerLabel, BorderLayout.NORTH)
                    add(statusLabel, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun buildToolbar(): JComponent {
        val actions = DefaultActionGroup().apply {
            add(
                action("Copy command", AllIcons.Actions.Copy) {
                    CopyPasteManager.getInstance().setContents(StringSelection(commandField.text))
                    statusLabel.text = "Command copied."
                },
            )
            add(
                action("Copy output", AllIcons.Actions.ListFiles) {
                    CopyPasteManager.getInstance().setContents(StringSelection(outputBuffer.toString()))
                    statusLabel.text = "Output copied."
                },
            )
            add(action("Clear output", AllIcons.Actions.GC) { clearOutput() })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun action(text: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, text, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    // ---------------------------------------------------------------- behaviour

    private fun wire() {
        runButton.addActionListener { execute() }
        cancelButton.addActionListener {
            runner.cancel()
            statusLabel.text = "Cancelling…"
        }
        commandField.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = refreshDangerHint()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = refreshDangerHint()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = refreshDangerHint()
            },
        )
        commandField.registerKeyboardAction(
            { execute() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
        favouriteButton.addActionListener { toggleFavourite() }
        historyCombo.addActionListener {
            (historyCombo.selectedItem as? String)
                ?.removePrefix(FAVOURITE_PREFIX)
                ?.let { commandField.text = it }
        }
        searchField.registerKeyboardAction(
            { findNext() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
    }

    private fun execute() {
        val target = device ?: run {
            statusLabel.text = "No device selected."
            return
        }
        val command = commandField.text.trim()
        if (command.isEmpty()) return
        if (runner.isRunning) {
            statusLabel.text = "A command is already running. Cancel it first."
            return
        }
        if (!confirmIfDangerous(command, target)) return

        history.record(command)
        refreshHistory()

        appendLine("$ $command")
        setRunning(true)

        runner.run(
            device = target.device,
            command = command,
            timeoutSeconds = CommandRunner.DEFAULT_TIMEOUT_SECONDS,
            onLine = { line ->
                ApplicationManager.getApplication().invokeLater({ appendLine(line) }) { project.isDisposed }
            },
            onFinished = { failure ->
                ApplicationManager.getApplication().invokeLater({
                    setRunning(false)
                    statusLabel.text = failure?.let { "Failed: ${it.message}" } ?: "Done."
                    if (failure != null) appendLine("[error] ${failure.message}")
                }) { project.isDisposed }
            },
        )
    }

    /**
     * Blocks obviously catastrophic commands outright and confirms destructive ones.
     *
     * Shares [DangerousCommands] with the MCP tool, so a command needing confirmation for an
     * AI agent needs it here too.
     */
    private fun confirmIfDangerous(command: String, target: ConnectedDevice): Boolean =
        when (DangerousCommands.classify(command)) {
            DangerousCommands.Verdict.SAFE -> true

            DangerousCommands.Verdict.REFUSED -> {
                statusLabel.text = "Refused: ${DangerousCommands.explain(command)}"
                appendLine("[refused] ${DangerousCommands.explain(command)}")
                false
            }

            DangerousCommands.Verdict.DESTRUCTIVE ->
                MessageDialogBuilder.yesNo(
                    "Run a Destructive Command?",
                    "On ${target.info.describe()}:\n\n    $command\n\n" +
                        "${DangerousCommands.explain(command)} This cannot be undone.",
                ).yesText("Run")
                    .noText("Cancel")
                    .asWarning()
                    .ask(project)
        }

    private fun toggleFavourite() {
        val command = commandField.text.trim()
        if (command.isEmpty()) return
        val added = history.toggleFavourite(command)
        favouriteButton.text = if (added) "★ Favourite" else "☆ Favourite"
        refreshHistory()
        statusLabel.text = if (added) "Added to favourites." else "Removed from favourites."
    }

    private fun refreshHistory() {
        val items = buildList {
            history.favourites().forEach { add("$FAVOURITE_PREFIX$it") }
            history.recent().forEach { add(it) }
        }
        historyCombo.model = javax.swing.DefaultComboBoxModel(items.toTypedArray())
        historyCombo.selectedIndex = -1
    }

    private fun setRunning(running: Boolean) {
        runButton.isEnabled = !running
        cancelButton.isEnabled = running
        statusLabel.text = if (running) "Running…" else statusLabel.text
    }

    private var showingHint = true

    private fun appendLine(line: String) {
        if (showingHint) {
            output.text = ""
            showingHint = false
        }
        outputBuffer.append(line).append('\n')
        // Keep the pane bounded: a `dumpsys` dump can run to megabytes.
        if (outputBuffer.length > MAX_OUTPUT_CHARS) {
            outputBuffer.delete(0, outputBuffer.length - MAX_OUTPUT_CHARS)
            output.text = "[earlier output trimmed]\n$outputBuffer"
        } else {
            output.append("$line\n")
        }
        output.caretPosition = output.document.length
    }

    private fun clearOutput() {
        outputBuffer.setLength(0)
        output.text = EMPTY_OUTPUT_HINT
        showingHint = true
        statusLabel.text = "Output cleared."
    }

    private fun findNext() {
        val needle = searchField.text
        if (needle.isEmpty()) return

        val from = output.caretPosition
        val text = output.text
        val index = text.indexOf(needle, from, ignoreCase = true)
            .takeIf { it >= 0 }
            ?: text.indexOf(needle, 0, ignoreCase = true)

        if (index < 0) {
            statusLabel.text = "'$needle' not found."
            return
        }
        output.select(index, index + needle.length)
        output.requestFocusInWindow()
        statusLabel.text = "Found '$needle'."
    }

    /**
     * Flags a destructive command while it is being typed.
     *
     * A confirmation dialog after pressing Run is easy to dismiss on autopilot; seeing the
     * warning appear as you type is what actually prevents the mistake.
     */
    private fun refreshDangerHint() {
        val command = commandField.text.orEmpty()
        when (DangerousCommands.classify(command)) {
            DangerousCommands.Verdict.SAFE -> {
                dangerLabel.text = " "
                dangerLabel.icon = null
            }
            DangerousCommands.Verdict.DESTRUCTIVE -> {
                dangerLabel.text = DangerousCommands.explain(command).orEmpty()
                dangerLabel.icon = AllIcons.General.Warning
            }
            DangerousCommands.Verdict.REFUSED -> {
                dangerLabel.text = "Refused: ${DangerousCommands.explain(command)}"
                dangerLabel.icon = AllIcons.General.Error
            }
        }
    }

    private fun updateStatus() {
        statusLabel.text = device?.let { "Target: ${it.info.describe()}" } ?: "No device selected."
    }

    override fun dispose() = runner.cancel()

    private companion object {
        const val MAX_OUTPUT_CHARS = 2_000_000
        const val SEARCH_COLUMNS = 16
        const val FAVOURITE_PREFIX = "★  "

        val EMPTY_OUTPUT_HINT = """
            Type an adb shell command above and press Run.

            Examples:
              pm list packages -3
              dumpsys battery
              ps -A

            Destructive commands are flagged as you type and confirmed before they run.
        """.trimIndent()
        const val GAP = 4
        const val STATUS_PAD_V = 2
        const val STATUS_PAD_H = 6
    }
}
