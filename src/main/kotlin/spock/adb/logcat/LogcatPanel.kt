package spock.adb.logcat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import spock.adb.ui.renderWith
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.Timer

/**
 * Live logcat, scoped to the app under development.
 *
 * Android Studio already has a capable Logcat window, so this one earns its place by being
 * pre-scoped: it defaults to the open project's application ID and offers presets for the
 * failures developers actually go looking for — crashes and ANRs — rather than being another
 * general-purpose log viewer.
 */
class LogcatPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val buffer = LogcatBuffer()
    private val listModel = DefaultListModel<LogcatEntry>()
    private val list = JBList(listModel)

    /**
     * Entries arrive on an ADB reader thread far faster than Swing can repaint. They queue
     * here and are drained on the EDT on a timer: appending per line would flood the event
     * queue and lock the UI on a busy device.
     */
    private val incoming = ConcurrentLinkedQueue<LogcatEntry>()
    private val flushTimer = Timer(FLUSH_INTERVAL_MS) { drainIncoming() }

    private val presetCombo = JComboBox(LogcatPreset.entries.toTypedArray()).apply {
        renderWith { it.label }
        toolTipText = "Filter preset"
    }
    private val levelCombo = JComboBox(LogLevel.entries.toTypedArray()).apply {
        renderWith { it.label }
        toolTipText = "Minimum log level"
    }
    private val searchField = JBTextField(SEARCH_COLUMNS)
    private val regexToggle = JCheckBox("Regex")
    private val autoScroll = JCheckBox("Auto-scroll", true)
    private val statusLabel = JBLabel(" ")

    private var stream: LogcatStream? = null
    private var device: ConnectedDevice? = null
    private var appPids: Set<Int> = emptySet()
    private var paused = false

    private var filter = LogcatFilter()

    init {
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.cellRenderer = LogcatCellRenderer()
        list.setEmptyText("Not streaming. Select a device and press Start.")

        setToolbar(buildToolbar())
        setContent(
            JPanel(BorderLayout()).apply {
                add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
                add(statusBar(), BorderLayout.SOUTH)
            },
        )

        wireFilterControls()
        flushTimer.isRepeats = true
        flushTimer.start()
        updateStatus()
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

        resolveAppPids(target)

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
     * Resolves the PIDs of the project's app so "Current app" can filter by process rather
     * than by matching the package name against message text, which both misses lines and
     * returns unrelated ones.
     */
    private fun resolveAppPids(target: ConnectedDevice) {
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
                applyFilter()
            }) { project.isDisposed }
        }
    }

    // ---------------------------------------------------------------- filtering

    private fun wireFilterControls() {
        presetCombo.addActionListener {
            val preset = presetCombo.selectedItem as LogcatPreset
            val presetFilter = preset.toFilter(appPids)
            // Reflect the preset in the controls so it is visible what is being applied,
            // and so the developer can adjust it rather than only replace it.
            levelCombo.selectedItem = presetFilter.minLevel
            searchField.text = presetFilter.query
            regexToggle.isSelected = presetFilter.useRegex
            applyFilter()
        }
        levelCombo.addActionListener { applyFilter() }
        regexToggle.addActionListener { applyFilter() }
        searchField.addKeyListener(
            object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) = applyFilter()
            },
        )
    }

    private fun applyFilter() {
        val preset = presetCombo.selectedItem as LogcatPreset
        filter = LogcatFilter(
            minLevel = levelCombo.selectedItem as LogLevel,
            query = searchField.text.orEmpty(),
            useRegex = regexToggle.isSelected,
            pids = if (preset == LogcatPreset.CURRENT_APP) appPids else emptySet(),
        )
        rebuildFromBuffer()
    }

    /** Re-applies the filter to everything received so far, not just to new lines. */
    private fun rebuildFromBuffer() {
        val matching = buffer.filtered(filter)
        listModel.clear()
        matching.forEach(listModel::addElement)
        scrollToEndIfFollowing()
        updateStatus()
    }

    private fun drainIncoming() {
        if (incoming.isEmpty()) return

        var appended = false
        while (true) {
            val entry = incoming.poll() ?: break
            if (filter.matches(entry)) {
                listModel.addElement(entry)
                appended = true
            }
        }
        if (appended) {
            trimModel()
            scrollToEndIfFollowing()
            updateStatus()
        }
    }

    /** Keeps the visible model bounded independently of the backing buffer. */
    private fun trimModel() {
        while (listModel.size() > VISIBLE_LIMIT) listModel.remove(0)
    }

    private fun scrollToEndIfFollowing() {
        if (autoScroll.isSelected && listModel.size() > 0) {
            list.ensureIndexIsVisible(listModel.size() - 1)
        }
    }

    private fun updateStatus() {
        val state = when {
            stream?.isRunning == true && paused -> "Paused"
            stream?.isRunning == true -> "Streaming"
            else -> "Stopped"
        }
        val deviceLabel = device?.info?.displayName ?: "no device"
        val invalid = if (filter.hasInvalidRegex) "  —  invalid regex" else ""
        statusLabel.text = "$state  ·  $deviceLabel  ·  ${listModel.size()} shown of ${buffer.size()}$invalid"
    }

    // ---------------------------------------------------------------- toolbar

    private fun buildToolbar(): JPanel {
        val actions = DefaultActionGroup().apply {
            add(
                simpleAction("Start", "Start streaming logcat", AllIcons.Actions.Execute) {
                    paused = false
                    start()
                },
            )
            add(simpleAction("Stop", "Stop streaming", AllIcons.Actions.Suspend) { stop() })
            add(
                simpleAction("Pause / Resume", "Freeze the view without stopping the stream", AllIcons.Actions.Pause) {
                    paused = !paused
                    if (!paused) drainIncoming()
                    updateStatus()
                },
            )
            addSeparator()
            add(
                simpleAction("Clear", "Clear the view and the device buffer", AllIcons.Actions.GC) {
                    buffer.clear()
                    incoming.clear()
                    listModel.clear()
                    stream?.clearDeviceBuffer()
                    updateStatus()
                },
            )
            add(simpleAction("Copy", "Copy the selected lines", AllIcons.Actions.Copy) { copySelection() })
            add(
                simpleAction(
                    "Export",
                    "Export the visible log to a file",
                    AllIcons.ToolbarDecorator.Export,
                ) { export() },
            )
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actions, true)
        toolbar.targetComponent = this

        // WrapLayout so the controls reflow instead of running off the edge of a narrow,
        // docked tool window.
        val filters = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(0, GAP, 2, GAP)
            add(JBLabel("Preset:"))
            add(presetCombo)
            add(JBLabel("Level:"))
            add(levelCombo)
            add(JBLabel("Search:"))
            add(searchField.apply { toolTipText = "Filter by message or tag" })
            add(regexToggle)
            add(autoScroll)
        }

        // Two rows, not one. The previous layout put the toolbar, the filters and the status
        // in a single BorderLayout row, so in a docked tool window the filter controls were
        // clipped vertically and the status text was truncated.
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(filters, BorderLayout.CENTER)
        }
    }

    /** Status on its own row: in the old layout it lived in EAST and was truncated. */
    private fun statusBar(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, GAP)
        add(statusLabel, BorderLayout.WEST)
    }

    private fun simpleAction(text: String, description: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, description, icon), Toggleable {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun copySelection() {
        val selected = list.selectedValuesList
        val text = when {
            selected.isNotEmpty() -> selected.joinToString("\n") { it.raw }
            else -> (0 until listModel.size()).joinToString("\n") { listModel.get(it).raw }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        statusLabel.text = "Copied ${text.lines().size} lines."
    }

    private fun export() {
        // The two-argument constructor does not exist before 2025.1 — Plugin Verifier caught
        // it as a NoSuchMethodError risk on AI-231, AI-242 and IC-231. The vararg overload is
        // deprecated on newer platforms but present on all supported ones, and a deprecation
        // warning is strictly better than a crash. See docs/COMPATIBILITY.md.
        @Suppress("DEPRECATION")
        val descriptor = FileSaverDescriptor("Export Logcat", "Save the visible log lines", "txt", "log")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        // Disambiguate the overload: save(VirtualFile?, String) vs save(Path?, String).
        val target = dialog.save(null as java.nio.file.Path?, "logcat.txt") ?: return

        runCatching {
            target.file.writeText((0 until listModel.size()).joinToString("\n") { listModel.get(it).raw })
        }.onSuccess {
            statusLabel.text = "Exported ${listModel.size()} lines to ${target.file.name}."
        }.onFailure {
            statusLabel.text = "Export failed: ${it.message}"
        }
    }

    override fun dispose() {
        flushTimer.stop()
        stop()
    }

    // ---------------------------------------------------------------- rendering

    private class LogcatCellRenderer : ListCellRenderer<LogcatEntry> {
        private val label = JBLabel().apply { border = JBUI.Borders.empty(0, CELL_PAD_H) }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out LogcatEntry>,
            value: LogcatEntry,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            label.text = value.render()
            // Monospace: the timestamp, PID and level columns only line up in a fixed-width
            // font, and a ragged log is much harder to scan.
            label.font = JBUI.Fonts.create(Font.MONOSPACED, list.font.size)
            label.isOpaque = true
            label.background = if (isSelected) list.selectionBackground else list.background
            label.foreground = when {
                isSelected -> list.selectionForeground
                else -> colourFor(LogcatHighlighter.classify(value))
            }
            return label
        }

        private fun LogcatEntry.render(): String = when {
            timestamp.isEmpty() -> message
            else -> "$timestamp  $pid-$tid  ${level.code}/$tag: $message"
        }

        private fun colourFor(highlight: LogcatHighlighter.Highlight): JBColor = when (highlight) {
            // Crashes and ANRs are what the developer opened the panel for, so they get the
            // strongest colour; warnings are dimmed rather than shouted.
            LogcatHighlighter.Highlight.CRASH -> CRASH
            LogcatHighlighter.Highlight.ANR -> ANR
            LogcatHighlighter.Highlight.ERROR -> ERROR
            LogcatHighlighter.Highlight.WARNING -> WARNING
            LogcatHighlighter.Highlight.NONE -> NORMAL
        }

        private companion object {
            const val CELL_PAD_H = 6
            val CRASH = JBColor(0xC7222A, 0xFF6B6B)
            val ANR = JBColor(0x9C27B0, 0xCE93D8)
            val ERROR = JBColor(0xB3261E, 0xF2857C)
            val WARNING = JBColor(0x8A6100, 0xE0A030)
            val NORMAL = JBColor(0x2B2B2B, 0xBBBBBB)
        }
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 100
        const val VISIBLE_LIMIT = 10_000
        const val SEARCH_COLUMNS = 18
        const val PIDOF_TIMEOUT_SECONDS = 10L
        const val GAP = 4
    }
}
