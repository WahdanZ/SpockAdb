package spock.adb.logcat

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.assistant.AssistantFeature
import spock.adb.assistant.AssistantPrefill
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import spock.adb.ui.renderWith
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Live logcat, scoped to the app under development.
 *
 * Android Studio already has a capable Logcat window, so this one earns its place by being
 * pre-scoped and by being the shortest path from a log line to an answer: the scope defaults to
 * the open project's app, the failures worth interrupting a scan for are separated from the
 * levels, and a screenful can be handed to the plugin's own Assistant as prepared context
 * rather than as a wall of raw text.
 *
 * The toolbar carries only what is used continuously — live, scope, filter, search, Ask AI.
 * Everything else lives behind **⋯**: exposing fifteen controls at equal weight made a docked
 * tool window mostly chrome, which is the opposite of what a log viewer is for.
 */
class LogcatPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val buffer = LogcatBuffer()
    private val details = LogcatDetailsPanel()

    /**
     * Remembered across sessions: which view a developer prefers is a habit, not a per-run
     * decision, and having to re-choose it on every IDE start would be its own annoyance.
     */
    private var editorView = PropertiesComponent.getInstance().getBoolean(EDITOR_VIEW_KEY, true)

    private var view: LogcatView = newView()

    /**
     * Entries arrive on an ADB reader thread far faster than Swing can repaint. They queue
     * here and are drained on the EDT on a timer: appending per line would flood the event
     * queue and lock the UI on a busy device.
     */
    private val incoming = java.util.concurrent.ConcurrentLinkedQueue<LogcatEntry>()
    private val flushTimer = Timer(FLUSH_INTERVAL_MS) { drainIncoming() }

    private val scopeCombo = JComboBox(LogcatScope.entries.toTypedArray()).apply {
        renderWith { it.label }
        toolTipText = "Which processes to show"
    }
    private val intentCombo = JComboBox(LogcatIntent.entries.toTypedArray()).apply {
        renderWith { it.label }
        toolTipText = "What to look for, within the chosen scope"
    }
    private val levelCombo = JComboBox(LogLevel.entries.toTypedArray()).apply {
        renderWith { "${it.label}+" }
        toolTipText = "Minimum log level"
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search logs"
        textEditor.columns = SEARCH_COLUMNS
        toolTipText = "Filter by message or tag"
    }
    private val statusLabel = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground() }

    /** Status on its own row: in the old layout it lived in EAST and was truncated. */
    private val statusRow = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, GAP)
        add(statusLabel, BorderLayout.WEST)
    }

    private val splitter = OnePixelSplitter(true, SPLIT_PROPORTION).apply {
        // Each view brings its own scrolling — the editor's own model, or the list's scroll
        // pane — so nothing is wrapped here.
        firstComponent = view.component
        secondComponent = details
    }

    /** Set by the tool window so Logcat can hand a prompt to the Assistant tab. */
    var assistant: AssistantPrefill? = null

    private var stream: LogcatStream? = null
    private var device: ConnectedDevice? = null
    private var appPids: Set<Int> = emptySet()
    private var appPackage: String = ""
    private var paused = false

    private var useRegex = false
    private var autoScroll = true
    private var showDetails = true

    private var filter = LogcatFilter()

    init {
        adoptView()

        details.onClose = {
            showDetails = false
            refreshDetails()
        }
        details.onCopied = { message -> statusLabel.text = message }

        setToolbar(buildToolbar())
        setContent(
            JPanel(BorderLayout()).apply {
                add(splitter, BorderLayout.CENTER)
                add(statusRow, BorderLayout.SOUTH)
            },
        )

        wireFilterControls()
        refreshDetails()
        flushTimer.isRepeats = true
        flushTimer.start()
        updateStatus()
    }

    /**
     * The two ways anyone actually copies a log line: the keyboard, and the right button.
     *
     * Copy lived only in the overflow menu, which is three clicks away and the wrong place for
     * the single most common thing done to a log line. The platform's own copy shortcut is what
     * a developer will reach for first, so it is bound to the list rather than invented.
     */
    /** Shared by both views, and re-attached on every swap. */
    private val popupListener = object : MouseAdapter() {
        // Checked on press and release: which one carries the popup trigger is
        // platform-specific, and on macOS only one of them does.
        override fun mousePressed(event: MouseEvent) = maybeShowMenu(event)
        override fun mouseReleased(event: MouseEvent) = maybeShowMenu(event)

        private fun maybeShowMenu(event: MouseEvent) {
            if (!event.isPopupTrigger) return
            view.focusLineAt(event.point)
            showRowMenu(event)
        }
    }

    private fun showRowMenu(event: MouseEvent) {
        val block = view.caretIndex()?.let { LogcatGroup.at(view.entries(), it) }

        val group = DefaultActionGroup().apply {
            add(
                simpleAction("Copy Lines", "The raw records, as the device sent them", AllIcons.Actions.Copy) {
                    copyRaw()
                },
            )
            add(simpleAction("Copy Messages", "The messages alone, without the log prefix", null) { copyMessages() })
            if (block != null && block.isMultiLine) {
                val what = block.kind.label.lowercase()
                add(
                    simpleAction("Copy ${block.kind.label}", "Every line of the block this record is part of", null) {
                        val copied = LogcatClipboard.copy(block.render())
                        statusLabel.text = "Copied a $copied-line $what."
                    },
                )
            }
            if (AssistantFeature.LOGCAT_HANDOFF_VISIBLE) {
                addSeparator()
                addAll(aiActions())
            }
        }

        val context = DataManager.getInstance().getDataContext(view.contentComponent)
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, context, false, null, MENU_ROWS)
            .show(RelativePoint(event))
    }

    /** Builds the view the preference asks for. */
    private fun newView(): LogcatView = if (editorView) LogcatEditorView(project) else LogcatListView()

    /**
     * Wires a freshly built view into the panel.
     *
     * Everything a view needs from the panel is set here rather than at construction, so the
     * two implementations stay interchangeable and a swap is the same work as a first build.
     */
    private fun adoptView() {
        Disposer.register(this, view)
        view.onSelectionChanged = { refreshDetails() }
        view.installCopyShortcut { copyRaw() }
        view.contentComponent.addMouseListener(popupListener)
    }

    /**
     * Swaps the view, keeping what is on screen.
     *
     * Refilled from the buffer rather than copied across: the buffer is the record of what
     * arrived, and re-running the filter is both simpler and guaranteed to agree with the
     * controls. The old view is disposed at once — an editor that is not released is a leak the
     * platform reports at shutdown, and waiting for the panel's own disposal would keep every
     * view a developer ever toggled through.
     */
    private fun useEditorView(enabled: Boolean) {
        if (enabled == editorView) return
        editorView = enabled
        PropertiesComponent.getInstance().setValue(EDITOR_VIEW_KEY, enabled, true)

        val previous = view
        view = newView()
        adoptView()
        splitter.firstComponent = view.component
        Disposer.dispose(previous)

        rebuildFromBuffer()
        splitter.revalidate()
        splitter.repaint()
    }

    // ---------------------------------------------------------------- streaming

    /** Called when the tool window's selected device changes. */
    fun setDevice(connected: ConnectedDevice?) {
        if (connected?.serialNumber == device?.serialNumber) return
        stop()
        device = connected
        updateStatus()
    }

    fun start() {
        val target = device ?: run {
            statusLabel.text = "No device selected."
            return
        }
        if (stream?.isRunning == true) return

        resolveApp(target)

        val logcatStream = LogcatStream(
            device = target.device,
            onEntry = { entry ->
                buffer.add(entry)
                if (!paused) incoming.add(entry)
            },
            onStopped = { error ->
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = error?.let { "Stream ended: ${it.message}" } ?: "Stopped."
                }) { project.isDisposed }
            },
        )
        stream = logcatStream
        logcatStream.start()
        updateStatus()
    }

    fun stop() {
        stream?.stop()
        stream = null
        updateStatus()
    }

    /**
     * Resolves the app id and the PIDs it is running as, so the App scope filters by process
     * rather than by matching the package name against message text — which both misses lines
     * and returns unrelated ones.
     */
    private fun resolveApp(target: ConnectedDevice) {
        val applicationId = runCatching {
            spock.adb.command.GetApplicationIDCommand.resolve(project)
        }.getOrNull() ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val pids = runCatching {
                val receiver = spock.adb.ShellOutputReceiver()
                target.device.executeShellCommand(
                    "pidof ${spock.adb.ShellQuote.quote(applicationId)}",
                    receiver,
                    PIDOF_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS,
                )
                receiver.toString().trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull).toSet()
            }.getOrDefault(emptySet())

            ApplicationManager.getApplication().invokeLater({
                appPids = pids
                appPackage = applicationId
                applyFilter()
            }) { project.isDisposed }
        }
    }

    // ---------------------------------------------------------------- filtering

    private fun wireFilterControls() {
        scopeCombo.addActionListener { applyFilter() }
        intentCombo.addActionListener { applyFilter() }
        levelCombo.addActionListener { applyFilter() }
        searchField.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = applyFilter()
                override fun removeUpdate(event: DocumentEvent) = applyFilter()
                override fun changedUpdate(event: DocumentEvent) = applyFilter()
            },
        )
    }

    /**
     * Rebuilds the filter from the controls.
     *
     * Scope and intent are read independently and neither writes back to the other: the old
     * preset list set the level and the search box as a side effect of choosing what to look
     * for, so narrowing to crashes silently widened the view to every process on the device.
     */
    private fun applyFilter() {
        filter = LogcatFilter(
            scope = scopeCombo.selectedItem as LogcatScope,
            intent = intentCombo.selectedItem as LogcatIntent,
            minLevel = levelCombo.selectedItem as LogLevel,
            query = searchField.text.orEmpty(),
            useRegex = useRegex,
            appPids = appPids,
            appPackage = appPackage,
        )
        rebuildFromBuffer()
    }

    /**
     * Brings the view back to the present after a pause.
     *
     * The lines that arrived while paused are in the buffer but were never queued for the view,
     * so resuming by draining the queue showed nothing and left a silent gap in the log. The
     * buffer is re-read instead, and the queue is emptied afterwards so a line caught by both
     * cannot be appended twice.
     */
    private fun resumeFromBuffer() {
        rebuildFromBuffer()
        incoming.clear()
    }

    /** Re-applies the filter to everything received so far, not just to new lines. */
    private fun rebuildFromBuffer() {
        view.setAll(buffer.filtered(filter), autoScroll)
        refreshDetails()
        updateStatus()
    }

    private fun drainIncoming() {
        if (incoming.isEmpty()) return

        // Collected and appended in one document edit: a write action per line would be an
        // order of magnitude more work on the EDT than the old per-row model insert.
        val batch = mutableListOf<LogcatEntry>()
        while (true) {
            val entry = incoming.poll() ?: break
            if (filter.matches(entry)) batch += entry
        }
        if (batch.isNotEmpty()) {
            view.append(batch, autoScroll)
            updateStatus()
        }
    }

    private fun updateStatus() {
        val state = when {
            stream?.isRunning == true && paused -> "Paused"
            stream?.isRunning == true -> "Live"
            else -> "Stopped"
        }
        val deviceLabel = device?.info?.displayName ?: "no device"
        val invalid = if (filter.hasInvalidRegex) "  ·  invalid regex" else ""
        // The same admission the AI context makes: a scope that names the app while showing
        // every process is the panel's most misleading state, and it looks exactly like a
        // working one.
        val fallback = when {
            filter.isScopeApplied -> ""
            else -> "  ·  ${filter.scope.label} scope not applied — the app is not running"
        }
        statusLabel.text =
            "$state  ·  $deviceLabel  ·  ${view.size()} shown of ${buffer.size()}$fallback$invalid"
    }

    // ---------------------------------------------------------------- details

    /**
     * What the details pane is about: the block around one row, or the rows that were chosen.
     *
     * A selection of several rows used to hide the pane entirely, which read as multi-line
     * selection not being supported at all — the one case where the developer has said most
     * clearly what they are interested in.
     */
    private fun refreshDetails() {
        val chosen = view.selectedEntries()
        val single = view.caretIndex()
        details.isVisible = showDetails && chosen.isNotEmpty()
        if (details.isVisible) {
            val anchor = chosen.first()
            val group = when (single) {
                null -> LogcatGroup.ofSelection(chosen)
                else -> LogcatGroup.at(view.entries(), single)
            }
            // The app is named only when the line really came from it, so the field cannot lie.
            val owner = appPackage.takeIf { it.isNotBlank() && anchor.pid in appPids }
            details.show(anchor, group, owner)
        }
        splitter.revalidate()
        splitter.repaint()
    }

    // ---------------------------------------------------------------- toolbar

    /**
     * One row: `● Live | App ▾ | Info+ ▾ | All logs ▾ | search | Ask AI ▾ | ⋯`.
     *
     * Laid out with [WrapLayout] rather than in an action toolbar so the controls reflow onto a
     * second line in a narrow docked window instead of being clipped — and the two action
     * toolbars are separate components so Live stays at the start of the run and ⋯ at the end.
     */
    private fun buildToolbar(): JPanel {
        val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, GAP)
            add(toolbarFor(DefaultActionGroup(LiveAction())))
            add(scopeCombo)
            add(levelCombo)
            add(intentCombo)
            add(searchField)
            if (AssistantFeature.LOGCAT_HANDOFF_VISIBLE) {
                add(
                    JButton("Ask AI ▾", AllIcons.Actions.IntentionBulb).apply {
                        toolTipText = "Hand what is on screen to the Spock Assistant, or copy it as context"
                        addActionListener { showPopup(DefaultActionGroup(aiActions()), this) }
                    },
                )
            }
            add(toolbarFor(DefaultActionGroup(OverflowAction())))
        }
        return JPanel(BorderLayout()).apply { add(row, BorderLayout.CENTER) }
    }

    private fun toolbarFor(group: DefaultActionGroup): JComponent {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    /**
     * The one control that changes what the stream is doing.
     *
     * Start, Stop and Pause were three buttons of equal weight for what a developer thinks of
     * as one switch. Stop is still there — in ⋯ — for the case that actually needs it: letting
     * go of the device.
     */
    private inner class LiveAction : AnAction() {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val live = stream?.isRunning == true && !paused
            event.presentation.icon = if (live) AllIcons.Actions.Pause else AllIcons.Actions.Execute
            event.presentation.text = if (live) "Pause" else "Live"
            event.presentation.description = when {
                live -> "Freeze the view. The stream keeps running, so nothing is missed."
                stream?.isRunning == true -> "Show the lines received while paused, and follow again"
                else -> "Start streaming logcat from the selected device"
            }
        }

        override fun actionPerformed(event: AnActionEvent) {
            if (stream?.isRunning != true) {
                paused = false
                start()
                return
            }
            paused = !paused
            if (!paused) resumeFromBuffer()
            updateStatus()
        }
    }

    private fun aiActions(): List<AnAction> = listOf(
        simpleAction("Ask Spock Assistant", "Open the Assistant with these logs prefilled", null) {
            askAssistant()
        },
        simpleAction("Copy for AI", "Copy the same prepared context to the clipboard", null) {
            copyForAi()
        },
    )

    private fun showPopup(group: DefaultActionGroup, anchor: JComponent) {
        val context = DataManager.getInstance().getDataContext(anchor)
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, context, false, null, MENU_ROWS)
            .showUnderneathOf(anchor)
    }

    /**
     * The **⋯** button.
     *
     * A plain action that opens the menu itself, rather than a `DefaultActionGroup` marked
     * popup: an action toolbar flattens such a group straight onto the toolbar, which put Stop,
     * Clear, Copy, Export and three icon-less toggles back in the row this redesign exists to
     * empty. Opening the popup by hand is the one behaviour that is the same on every supported
     * IDE build.
     */
    private inner class OverflowAction : AnAction("More", "Everything else", AllIcons.Actions.More) {

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(event: AnActionEvent) {
            val anchor = event.inputEvent?.component as? JComponent ?: this@LogcatPanel
            showPopup(overflowGroup(), anchor)
        }
    }

    /**
     * Everything that is reached deliberately rather than continuously.
     *
     * Regex and auto-scroll are toggles rather than checkboxes on the toolbar: they are set once
     * and then left alone, and two checkboxes cost more horizontal room in a docked window than
     * the whole search field.
     */
    private fun overflowGroup(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(
            simpleAction("Stop Streaming", "Disconnect from the device's log", AllIcons.Actions.Suspend) { stop() },
        )
        group.add(
            simpleAction("Clear", "Clear the view and the device buffer", AllIcons.Actions.GC) {
                buffer.clear()
                incoming.clear()
                view.clear()
                stream?.clearDeviceBuffer()
                refreshDetails()
                updateStatus()
            },
        )
        group.addSeparator()
        group.add(
            simpleAction("Copy Lines", "Copy the lines exactly as the device sent them", AllIcons.Actions.Copy) {
                copyRaw()
            },
        )
        group.add(
            simpleAction("Export…", "Write the visible log to a file", AllIcons.ToolbarDecorator.Export) {
                statusLabel.text = LogcatClipboard.export(project, view.entries())
            },
        )
        group.addSeparator()
        group.add(
            toggle("Regular Expression", "Read the search box as a regex", { useRegex }) { value ->
                useRegex = value
                applyFilter()
            },
        )
        group.add(
            toggle("Auto-Scroll to Newest", "Follow the end of the log", { autoScroll }) { value ->
                autoScroll = value
                if (autoScroll) view.scrollToEnd()
            },
        )
        group.add(
            toggle("Show Details", "Open a details pane for the selected line", { showDetails }) { value ->
                showDetails = value
                refreshDetails()
            },
        )
        group.add(
            toggle(
                "Row View",
                "Draw each record as a row, with a coloured level chip. Off draws the log as " +
                    "text, so a selection can run across lines the way it does in a terminal.",
                { !editorView },
            ) { value -> useEditorView(!value) },
        )
        return group
    }

    // ---------------------------------------------------------------- AI

    /**
     * What the model is given: the selection if there is one, otherwise the filtered view —
     * never the raw buffer, which is mostly framework chatter and would cost a fortune to send.
     */
    private fun aiRequest() = LogcatAiRequest(
        selected = view.selectedEntries(),
        visible = view.entries(),
        appPackage = appPackage,
        deviceLabel = device?.info?.let { info ->
            listOfNotNull(info.displayName, info.androidVersionLabel().takeIf(String::isNotBlank))
                .joinToString(" · ")
        }.orEmpty(),
        filter = filter,
    )

    private fun askAssistant() {
        val target = assistant ?: run {
            statusLabel.text = "The Assistant tab is not available."
            return
        }
        val context = LogcatAiContextBuilder.build(aiRequest())
        if (context.lineCount == 0) {
            statusLabel.text = "Nothing to send — no lines match the current filter."
            return
        }
        target.prefill(context.asPrompt())
        statusLabel.text = "${context.summary()} Opened in the Assistant — review it, then press Send."
    }

    private fun copyForAi() {
        val context = LogcatAiContextBuilder.build(aiRequest())
        if (context.lineCount == 0) {
            statusLabel.text = "Nothing to copy — no lines match the current filter."
            return
        }
        LogcatClipboard.copy(context.text)
        statusLabel.text = "${context.summary()} Copied."
    }

    // ---------------------------------------------------------------- clipboard and files

    /** The lines a copy acts on: the selection, or the whole visible view when there is none. */
    private fun copyTarget(): List<LogcatEntry> =
        view.selectedEntries().takeIf { it.isNotEmpty() } ?: view.entries()

    private fun copyRaw() {
        val copied = LogcatClipboard.copy(LogcatClipboard.rawText(copyTarget()))
        statusLabel.text = "Copied $copied line(s), as the device sent them."
    }

    private fun copyMessages() {
        val copied = LogcatClipboard.copy(LogcatClipboard.messageText(copyTarget()))
        statusLabel.text = "Copied $copied message(s), without the log prefix."
    }

    override fun dispose() {
        flushTimer.stop()
        stop()
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 100
        const val VISIBLE_LIMIT = 10_000
        const val SEARCH_COLUMNS = 16
        const val PIDOF_TIMEOUT_SECONDS = 10L
        const val GAP = 4
        const val MENU_ROWS = 8
        const val SPLIT_PROPORTION = 0.68f
        const val EDITOR_VIEW_KEY = "spock.logcat.editorView"
    }
}

/** Toolbar and menu entries are one-liners; these keep the boilerplate out of the panel. */
private fun simpleAction(text: String, description: String, icon: javax.swing.Icon?, run: () -> Unit) =
    object : AnAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = run()
    }

private fun toggle(text: String, description: String, get: () -> Boolean, set: (Boolean) -> Unit) =
    object : ToggleAction(text, description, null) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = get()
        override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
    }
