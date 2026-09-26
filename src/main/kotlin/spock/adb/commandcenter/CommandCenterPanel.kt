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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.command.installedPackages
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.DefaultComboBoxModel
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

    private val commandField = JBTextField().apply {
        // The label to the left says `adb shell`; this says what goes after it.
        emptyText.text = "pm list packages -3"
    }
    private val historyCombo = JComboBox<String>()
    private val favouritesCombo = JComboBox<String>()

    /** The device the command will run on, beside the field rather than under the output. */
    private val targetLabel = JBLabel(" ").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        setFontColor(UIUtil.FontColor.BRIGHTER)
    }

    /** Running, completed, failed or cancelled — beside the output it is about. */
    private val runStateLabel = JBLabel(" ")
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        // An empty pane gives no hint that anything works; say what to do instead.
        text = EMPTY_OUTPUT_HINT
    }
    private val runButton = JButton("Run")
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }
    private val favouriteButton = JButton("☆ Add to favourites")
    private val statusLabel = JBLabel(" ")
    private val dangerLabel = JBLabel(" ")
    private val searchField = JBTextField(SEARCH_COLUMNS)

    private var device: ConnectedDevice? = null

    /** The selected device's third-party packages, offered wherever a command takes one. */
    @Volatile
    private var installedPackages: List<String> = emptyList()

    /** The device [installedPackages] belong to. */
    private var packagesSerial: String? = null
    private val completion = CommandCompletionPopup(commandField, packages = { installedPackages })
    private val outputBuffer = StringBuilder()

    /** When the running command started, for the duration reported when it ends. */
    private var startedAt = 0L

    /** The exit status the command reported, or null when it never got to say. */
    private var exitCode: Int? = null

    init {
        setToolbar(buildToolbar())
        setContent(buildContent())
        wire()
        updateStatus()
    }

    fun setDevice(connected: ConnectedDevice?) {
        device = connected
        updateStatus()
        loadPackages(connected)
    }

    private fun loadPackages(connected: ConnectedDevice?) {
        val serial = connected?.serialNumber
        // Every change to the device list announces the selection again; the same device keeps
        // the packages it already has instead of going blank while they are asked for again.
        if (serial == packagesSerial && installedPackages.isNotEmpty()) return
        if (serial != packagesSerial) installedPackages = emptyList()
        packagesSerial = serial
        connected ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = runCatching { connected.device.installedPackages() }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({
                // A slow device answering after the selection moved on must not win.
                if (packagesSerial == serial) installedPackages = found
            }) { project.isDisposed }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildContent(): JComponent {
        // Run stays beside the field; the rest wrap onto their own line, so a tool window
        // docked at 300px shrinks the field rather than clipping the buttons off the edge.
        commandField.minimumSize = java.awt.Dimension(0, commandField.preferredSize.height)
        val input = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            border = JBUI.Borders.empty(GAP, GAP, 0, GAP)
            add(JBLabel("adb shell"), BorderLayout.WEST)
            add(commandField, BorderLayout.CENTER)
            add(runButton, BorderLayout.EAST)
        }

        val actions = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, GAP, 0, GAP)
            add(cancelButton)
            add(favouriteButton)
            add(JBLabel("History:"))
            add(historyCombo)
            add(JBLabel("Favourites:"))
            add(favouritesCombo)
        }

        // The target sits with the command, not at the bottom of the panel: what a command is
        // about to run against is part of reading the command.
        val target = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, GAP, 0, GAP)
            add(targetLabel, BorderLayout.WEST)
        }

        // Find searches the output, so it sits with the output rather than with the input.
        val outputBar = JPanel(BorderLayout(JBUI.scale(GAP), 0)).apply {
            border = JBUI.Borders.empty(2, GAP)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
                    add(JBLabel("Find in output:"))
                    add(searchField)
                },
                BorderLayout.WEST,
            )
            add(runStateLabel, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            add(
                JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                    listOf(input, actions, target, outputBar).forEach {
                        it.alignmentX = LEFT_ALIGNMENT
                        it.maximumSize = java.awt.Dimension(Int.MAX_VALUE, it.preferredSize.height)
                        add(it)
                    }
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
            recall(historyCombo.selectedItem as? String)
        }
        favouritesCombo.addActionListener {
            recall(favouritesCombo.selectedItem as? String)
        }
        searchField.registerKeyboardAction(
            { findNext() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
    }

    private fun execute() {
        val target = device ?: run {
            statusLabel.text = NO_DEVICE
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
            // The device is asked for the exit status in the same shell, because ddmlib's
            // shell gives none: without this a command that failed and one that printed
            // nothing look exactly alike. The marker line is consumed, not shown.
            command = "$command; echo $EXIT_MARKER$?",
            timeoutSeconds = CommandRunner.DEFAULT_TIMEOUT_SECONDS,
            onLine = { line ->
                ApplicationManager.getApplication().invokeLater({ onOutput(line) }) { project.isDisposed }
            },
            onFinished = { failure ->
                ApplicationManager.getApplication().invokeLater({
                    setRunning(false)
                    finish(failure)
                }) { project.isDisposed }
            },
        )
    }

    /** Swallows the exit-status line the command was asked to print, and shows the rest. */
    private fun onOutput(line: String) {
        val code = line.trim().takeIf { it.startsWith(EXIT_MARKER) }?.removePrefix(EXIT_MARKER)?.toIntOrNull()
        if (code != null) exitCode = code else appendLine(line)
    }

    /**
     * What happened, beside the output: which of the four states it ended in, how long it took,
     * and what the device made of it.
     *
     * "Done." covered a command that failed, one that was cancelled and one that worked.
     */
    private fun finish(failure: Throwable?) {
        val elapsed = "%.1f s".format((System.currentTimeMillis() - startedAt) / MILLIS_PER_SECOND)
        val code = exitCode
        when {
            failure != null -> {
                showState("✗ Failed after $elapsed", ERROR)
                statusLabel.text = "Failed: ${failure.message}"
                appendLine("[error] ${failure.message}")
            }
            // No status came back: the shell never reached the echo, which is what cancelling
            // it does — and what a timeout does too.
            code == null -> {
                showState("⊘ Stopped after $elapsed", JBColor.GRAY)
                statusLabel.text = "The command was cancelled or timed out before it finished."
            }
            code == 0 -> {
                showState("✓ Completed in $elapsed", SUCCESS)
                statusLabel.text = "Done."
            }
            else -> {
                showState("✗ Exit $code after $elapsed", ERROR)
                statusLabel.text = "The command exited with status $code."
            }
        }
    }

    private fun showState(text: String, colour: JBColor) {
        runStateLabel.text = text
        runStateLabel.foreground = colour
    }

    /** Puts a remembered command back in the field without running it. */
    private fun recall(chosen: String?) {
        val command = chosen?.substringAfter(ENTRY_SEPARATOR, chosen)?.trim() ?: return
        if (command.isNotEmpty()) commandField.text = command
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
        favouriteButton.text = if (added) "★ Remove from favourites" else "☆ Add to favourites"
        refreshHistory()
        statusLabel.text = if (added) "Added to favourites." else "Removed from favourites."
    }

    /**
     * The two lists, each in its own dropdown.
     *
     * Favourites used to be a starred handful at the top of the same list as fifty recent
     * commands — saved in one click and then hunted for.
     */
    private fun refreshHistory() {
        // The time is what tells two runs of the same command apart, and where in the session
        // it happened; the command itself follows it.
        val recent = history.recent().map { "${TIME_FORMAT.format(Date(it.at))}$ENTRY_SEPARATOR${it.command}" }
        historyCombo.model = DefaultComboBoxModel(recent.toTypedArray())
        historyCombo.selectedIndex = -1

        val favourites = history.favourites()
        favouritesCombo.model = DefaultComboBoxModel(favourites.toTypedArray())
        favouritesCombo.selectedIndex = -1
        favouritesCombo.isEnabled = favourites.isNotEmpty()
        favouritesCombo.toolTipText =
            if (favourites.isEmpty()) "Nothing saved yet — press Add to favourites" else "Saved commands"
    }

    private fun setRunning(running: Boolean) {
        runButton.isEnabled = !running
        cancelButton.isEnabled = running
        if (running) {
            startedAt = System.currentTimeMillis()
            exitCode = null
            showState("● Running…", JBColor.GRAY)
            statusLabel.text = "Running…"
        }
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
        val target = device
        targetLabel.text = target?.let { "Runs on ${it.info.describe()}" } ?: "No device selected"
        // "No device selected." is about the moment before a device arrived; left in place, it
        // contradicted the "Runs on …" line above it for as long as nothing was run.
        statusLabel.text = when {
            target == null -> NO_DEVICE
            statusLabel.text == NO_DEVICE -> " "
            else -> statusLabel.text
        }
        runButton.isEnabled = target != null && !runner.isRunning
    }

    override fun dispose() {
        runner.cancel()
        completion.dispose()
    }

    private companion object {
        const val NO_DEVICE = "No device selected."

        const val MAX_OUTPUT_CHARS = 2_000_000
        const val SEARCH_COLUMNS = 16

        /** Separates a history entry's time from the command it belongs to. */
        const val ENTRY_SEPARATOR = "   "

        /** What the command is asked to print its exit status as, on a line of its own. */
        const val EXIT_MARKER = "__spock_exit="

        const val MILLIS_PER_SECOND = 1000.0

        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val SUCCESS = JBColor(0x1F6F4A, 0x57BA8C)
        val ERROR = JBColor(0xB3261E, 0xF2857C)

        val EMPTY_OUTPUT_HINT = """
            Type an adb shell command above and press Run.
            Suggestions and docs appear as you type:
            ↑↓ to choose, Tab to insert, Ctrl+Space or Alt+Space to ask.

            Examples:
              pm list packages -3
              dumpsys battery
              ps -A

            Destructive commands are flagged as you type,
            and confirmed before they run.
        """.trimIndent()
        const val GAP = 4
        const val STATUS_PAD_V = 2
        const val STATUS_PAD_H = 6
    }
}
