package spock.adb.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import spock.adb.ui.CollapsibleSection
import spock.adb.ui.WrapLayout
import spock.adb.ui.renderWith
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A first-class panel for the MCP server, rather than a checkbox buried in Settings.
 *
 * Three things a developer needs to see at a glance and could not before: whether the server
 * is running, what an AI agent has actually done to their device, and how to connect a
 * client.
 */
class McpServerPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = McpServerService.getInstance()

    private val statusLabel = JBLabel()
    private val detailLabel = JBLabel()
    private val clientLabel = JBLabel()
    private val startStopButton = JButton()
    private val restartButton = JButton("Restart")

    /**
     * Copying and rotating the configuration, which is the part of this panel where a mistake
     * hands out a credential. See [McpConnectControls].
     */
    private val connectControls = McpConnectControls(
        project = project,
        service = service,
        say = ::say,
        onServerChanged = ::refreshStatus,
    )

    private val activityTable = McpActivityTable()
    private val detailArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        // An error long enough to matter is longer than a docked tool window is wide, and it
        // was the part that got cut off.
        lineWrap = true
        wrapStyleWord = true
        text = EMPTY_DETAIL
    }

    private val copyDetailsButton = JButton("Copy details")
    private val copyRequestButton = JButton("Copy request")
    private val copyResponseButton = JButton("Copy response")

    private val toolsPane = McpToolsPane()

    /**
     * What just happened, beside the button that did it.
     *
     * Kept apart from [detailLabel], which says what the server *is*: "Configuration copied"
     * used to replace the transports and the tool count, so a permanent fact was overwritten by
     * a passing one and never came back until the next status refresh.
     */
    private val feedbackLabel = JBLabel(" ").apply { foreground = JBColor.GRAY }

    /** Clears [feedbackLabel]: a message about something that happened once should not persist. */
    private val feedbackTimer = javax.swing.Timer(FEEDBACK_MS) { feedbackLabel.text = " " }.apply {
        isRepeats = false
    }

    private val searchField = JBTextField(SEARCH_COLUMNS)
    private val toolFilter = JComboBox<String>()
    private val outcomeFilter = JComboBox(McpHistoryFilter.Outcome.entries.toTypedArray()).apply {
        renderWith { it.name.lowercase().replaceFirstChar(Char::uppercase) }
    }

    private val callListener: (McpCall) -> Unit = { refreshActivityLater() }

    /**
     * The details view, which moves between two homes depending on how tall the panel is.
     *
     * Docked at the bottom of an IDE window the whole panel is often under 500px, and 28% of
     * that is three lines of a JSON schema — a pane too small to read but still taking room
     * from the list above it. Below the threshold the details become a collapsible section
     * instead, closed by default, so the list gets the whole panel until they are asked for.
     */
    /** The title lives on the enclosing section, so the row here is buttons only. */
    private val detailsPane: JComponent by lazy {
        JPanel(BorderLayout()).apply {
            add(
                JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
                    border = JBUI.Borders.empty(2, GAP)
                    add(copyDetailsButton.apply { addActionListener { copy(detailArea.text) } })
                    add(copyRequestButton.apply { addActionListener { copy(selected()?.arguments) } })
                    add(copyResponseButton.apply { addActionListener { copy(selected()?.result) } })
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(detailArea).apply {
                    // Stacked, this sits in BorderLayout.SOUTH and would take its preferred
                    // height — which for a text area holding a pretty-printed response is most
                    // of the panel, squeezing out the list it is supposed to be explaining.
                    // The splitter sizes by proportion, so this is ignored when split.
                    preferredSize = Dimension(0, JBUI.scale(MIN_DETAIL_HEIGHT))
                },
                BorderLayout.CENTER,
            )
        }
    }

    private val detailsSection by lazy { CollapsibleSection("Details", detailsPane, "mcp.details") }
    private val splitter = OnePixelSplitter(true, SPLIT_PROPORTION)
    private val bodyPanel = JPanel(BorderLayout())

    /**
     * How the body is arranged. Null until the first pass, so the first decision always applies.
     */
    private var arrangement: Arrangement? = null

    /** The last height seen, so a click on the section can re-evaluate without a resize. */
    private var lastHeight = 0

    /**
     * The splitter gives Details a share of the panel; stacking gives it a title bar at the
     * bottom and the tabs everything else.
     */
    private enum class Arrangement { SPLIT, STACKED }

    /** Set on dispose, so a transition still in flight cannot update a dead panel. */
    @Volatile
    private var disposed = false

    init {
        setToolbar(header())
        setContent(body())

        toolsPane.onSelected = { details ->
            detailArea.text = details ?: EMPTY_DETAIL
            detailArea.caretPosition = 0
            updateCopyButtons()
        }
        service.addCallListener(callListener)
        wire()
        refreshStatus()
        refreshActivity()
        // Nothing is selected on a panel that has just opened, so the two buttons that copy a
        // call have nothing to copy — and the details pane holds only its placeholder.
        updateCopyButtons()
    }

    // ------------------------------------------------------------------ header

    private fun header(): JComponent {
        val status = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(GAP)
            add(statusLabel)
            add(detailLabel)
        }

        val controls = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(0, GAP, GAP, GAP)
            add(startStopButton)
            add(restartButton)
            add(connectControls.copyButton)
            add(connectControls.rotateButton)
            add(JButton("Settings").apply { addActionListener { openSettings() } })
            add(feedbackLabel)
        }

        return JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(controls, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(0, GAP, GAP, GAP)
                    add(clientLabel, BorderLayout.WEST)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    // ------------------------------------------------------------------ body

    private fun body(): JComponent {
        activityTable.onSelected = { showDetails() }

        val filters = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, GAP)
            add(JBLabel("Search:"))
            add(searchField)
            add(JBLabel("Tool:"))
            add(toolFilter)
            // The dropdown said "Any", which answered a question nobody had asked yet.
            add(JBLabel("Result:"))
            add(outcomeFilter)
            add(JButton("Clear History").apply { addActionListener { clearHistory() } })
        }

        val activity = JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(activityTable, BorderLayout.CENTER)
        }

        // Activity and Tools share the detail pane below: both answer "what is this call /
        // this tool", so two separate detail views would be redundant in a narrow window.
        val tabs = JBTabbedPane().apply {
            addTab("Activity", activity)
            addTab("Tools (${ToolRegistry.all().size})", toolsPane)
        }
        splitter.firstComponent = tabs

        bodyPanel.addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(event: java.awt.event.ComponentEvent) =
                    applyDensity(tabs, bodyPanel.height)
            },
        )
        // Expanding Details in the stacked layout should move it into the splitter when there is
        // room, rather than waiting for a resize that may never come.
        detailsSection.onToggled = { applyDensity(tabs, lastHeight) }
        applyDensity(tabs, 0)
        return bodyPanel
    }

    /**
     * Arranges the body, and only when the arrangement actually changes.
     *
     * **The layout follows the developer's click; it never sets it.** An earlier version
     * collapsed Details on the way into the compact layout and restored a snapshot on the way
     * out, which meant expanding it while the panel was short was undone by the next resize.
     * Reading `isExpanded` instead of writing it makes that impossible: the splitter is used
     * when there is room *and* Details is open, and otherwise the section sits at the bottom,
     * where collapsed costs one title-bar row and open costs what the developer asked for.
     *
     * Rebuilding on every resize event would tear the details view down and back up dozens of
     * times while the tool window edge is dragged, losing the scroll position each time — so
     * this returns unless the answer has changed.
     */
    private fun applyDensity(tabs: JComponent, height: Int) {
        lastHeight = height
        // Height 0 is the pre-layout pass: assume roomy, since that is the docked default and
        // the first real resize corrects it before anything is on screen.
        val roomy = height == 0 || height >= MIN_LIST_HEIGHT + MIN_DETAIL_HEIGHT
        val wanted = if (roomy && detailsSection.isExpanded) Arrangement.SPLIT else Arrangement.STACKED
        if (wanted == arrangement) return
        arrangement = wanted

        bodyPanel.removeAll()
        if (wanted == Arrangement.SPLIT) {
            splitter.firstComponent = tabs
            splitter.secondComponent = detailsSection
            bodyPanel.add(splitter, BorderLayout.CENTER)
        } else {
            // Both components move to a new parent, which detaches them from the splitter's
            // container but leaves the splitter still referencing them. Its setters ignore a
            // component they already hold, so failing to clear them here makes the re-add
            // above a no-op on the way back: the tabs would be removed from bodyPanel and
            // never returned to the splitter, leaving an empty body with no filter row.
            splitter.firstComponent = null
            splitter.secondComponent = null
            bodyPanel.add(tabs, BorderLayout.CENTER)
            bodyPanel.add(detailsSection, BorderLayout.SOUTH)
        }
        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    // ------------------------------------------------------------------ state

    private fun wire() {
        startStopButton.addActionListener { if (service.isRunning) stopServer() else startServer() }
        restartButton.addActionListener { restartServer() }
        searchField.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyReleased(e: java.awt.event.KeyEvent) = refreshActivity()
            },
        )
        toolFilter.addActionListener { refreshActivity() }
        outcomeFilter.addActionListener { refreshActivity() }
    }

    private fun startServer() {
        beginTransition("Starting the MCP server…")
        service.startAsync { result -> onEdt { finishTransition(result.exceptionOrNull()) } }
    }

    private fun stopServer() {
        beginTransition("Stopping the MCP server…")
        service.stopAsync { onEdt { finishTransition() } }
    }

    /**
     * Stop and start are chained, not issued together: the second must not begin until the
     * first has released the sockets — which is the whole reason they are asynchronous.
     */
    private fun restartServer() {
        beginTransition("Restarting the MCP server…")
        service.stopAsync {
            service.startAsync { result -> onEdt { finishTransition(result.exceptionOrNull()) } }
        }
    }

    /**
     * The controls go quiet while a transition runs.
     *
     * Starting binds two sockets and stopping waits for live stdio sessions to end, so both
     * take long enough to notice — this used to happen on the EDT and froze the tool window.
     * Disabling the buttons also stops a second click starting a server that is already
     * starting.
     */
    private fun beginTransition(message: String) {
        statusLabel.text = "◌ $message"
        statusLabel.foreground = JBColor.GRAY
        detailLabel.text = " "
        startStopButton.isEnabled = false
        restartButton.isEnabled = false
        // Rotating mid-restart would race the stop/start that rotation itself performs.
        connectControls.setBusy(true)
    }

    /** [failure] is reported after the refresh, which would otherwise overwrite it. */
    private fun finishTransition(failure: Throwable? = null) {
        startStopButton.isEnabled = true
        refreshStatus()

        if (failure != null) {
            statusLabel.text = "Could not start: ${failure.message}"
            statusLabel.foreground = ERROR
        }
    }

    /** Back to the EDT, dropping the update when the panel or the project has gone. */
    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ block() }) { disposed || project.isDisposed }

    private fun refreshStatus() {
        val running = service.isRunning
        val toolCount = ToolRegistry.all().size

        statusLabel.text = if (running) "● MCP Server running" else "○ MCP Server stopped"
        statusLabel.foreground = if (running) RUNNING else JBColor.GRAY

        detailLabel.text = when {
            // Name the transports that are actually accepting connections. The stdio bridge
            // is reported only when it bound, since it can fail while HTTP keeps working.
            running -> "·  Transports: ${transports()}  ·  Tools: $toolCount"
            else -> "·  Not accepting connections  ·  Tools available: $toolCount"
        }
        detailLabel.foreground = JBColor.GRAY

        startStopButton.text = if (running) "Stop Server" else "Start MCP Server"
        startStopButton.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        restartButton.isEnabled = running
        connectControls.refresh(running)

        refreshClientLabel(running)
    }

    private fun transports(): String {
        val http = "HTTP (127.0.0.1:${service.port})"
        // The session count is stdio's alone. HTTP POST is stateless, so it has no sessions to
        // count, and showing a shared number would attribute stdio's connections to both.
        val stdio = service.stdioEndpoint?.let { "stdio (${it.describe()}$sessions)" }
        return listOfNotNull(http, stdio).joinToString(", ")
    }

    /**
     * Live stdio connections, shown only when there are any.
     *
     * A permanent "0 sessions" would read as something being wrong when nothing is: no client
     * is attached, which is the normal state of a server nobody has pointed a client at yet.
     */
    private fun requests(): String = when (val count = service.recentCalls().size) {
        1 -> "1 request"
        else -> "$count requests"
    }

    private val sessions: String get() = when (val count = service.stdioSessionCount) {
        0 -> ""
        1 -> ", 1 session"
        else -> ", $count sessions"
    }

    /**
     * Reports only what the transports actually tell us.
     *
     * Neither one knows who is calling until the client says so in `initialize`, for two
     * different reasons: plain HTTP POST is stateless, so there is no connection to be
     * "online" on at all, and a stdio session is a connection but still carries no identity
     * before that first message. Either way a per-client presence indicator would be invented
     * rather than observed, so none is shown.
     */
    private fun refreshClientLabel(running: Boolean) {
        val client = service.connectedClient()
        clientLabel.foreground = JBColor.GRAY
        // Wrapped as HTML so a plain JBLabel wraps rather than clipping in a docked tool
        // window — but only where the text is ours. A client's name is whatever it sent.
        clientLabel.text = when {
            !running -> " "
            client == null -> "<html>No client connected yet. Copy the configuration to connect one.</html>"
            else -> {
                val version = client.version?.let { " $it" }.orEmpty()
                "Last client: ${client.name}$version   ·   ${requests()}"
            }
        }
        // The protocol reason sits here rather than on the line: it explains why the panel
        // cannot say more, which is worth having once and not worth reading twice.
        clientLabel.toolTipText = if (running && client == null) UNIDENTIFIED_HINT else null
    }

    // ------------------------------------------------------------------ activity

    private fun refreshActivityLater() = onEdt { refreshActivity() }

    private fun refreshActivity() {
        val filter = McpHistoryFilter(
            query = searchField.text.orEmpty(),
            tool = (toolFilter.selectedItem as? String)?.takeIf { it != ANY_TOOL },
            outcome = outcomeFilter.selectedItem as McpHistoryFilter.Outcome,
        )

        // The table keeps the selected call selected when the filter still lets it through.
        activityTable.show(service.queryHistory(filter))
        refreshToolFilterOptions()
        refreshClientLabel(service.isRunning)
    }

    private fun refreshToolFilterOptions() {
        val expected = listOf(ANY_TOOL) + service.knownTools()
        val current = (0 until toolFilter.itemCount).map { toolFilter.getItemAt(it) }
        if (current == expected) return

        val selectedTool = toolFilter.selectedItem as? String
        toolFilter.model = javax.swing.DefaultComboBoxModel(expected.toTypedArray())
        toolFilter.selectedItem = selectedTool?.takeIf { it in expected } ?: ANY_TOOL
    }

    private fun selected(): McpCall? = activityTable.selected

    /** Says something that just happened, and takes it back down again. */
    private fun say(message: String) {
        feedbackLabel.text = message
        feedbackTimer.restart()
    }

    /** Copy request and copy response are about a call; with none selected they copy nothing. */
    private fun updateCopyButtons() {
        val call = selected()
        copyRequestButton.isEnabled = call != null
        copyResponseButton.isEnabled = call != null
        copyDetailsButton.isEnabled = detailArea.text != EMPTY_DETAIL
    }

    /**
     * What one call did, with what went wrong at the top.
     *
     * The failure used to be the third line of a metadata block and its message the last thing
     * in the pane, under the arguments — so diagnosing a failed call started with scrolling
     * past the request that caused it.
     */
    private fun showDetails() {
        val call = selected() ?: run {
            detailArea.text = EMPTY_DETAIL
            updateCopyButtons()
            return
        }
        detailArea.text = buildString {
            appendLine("Tool:     ${call.toolName}")
            appendLine("Access:   ${call.safety.describe()}")
            appendLine("Result:   ${if (call.isError) "Error" else "Success"}")
            if (call.isError) {
                appendLine()
                appendLine("Error")
                appendLine(call.result.trim())
            }
            appendLine()
            appendLine("Duration: ${call.durationMs} ms")
            appendLine("Time:     ${TIME_FORMAT.format(Date(call.timestamp))}")
            appendLine("Client:   ${call.client ?: "unidentified"}")
            appendLine("Device:   ${call.deviceSerial ?: "default (selected device)"}")
            if (call.safety == ToolSafety.DESTRUCTIVE) {
                appendLine("Approval: ${if (call.wasConfirmed) "approved by you" else "denied or failed"}")
            }
            appendLine()
            appendLine("Request")
            appendLine(readable(call.arguments))
            if (!call.isError) {
                appendLine()
                appendLine("Response")
                append(readable(call.result))
            }
        }
        detailArea.caretPosition = 0
        updateCopyButtons()
    }

    /**
     * JSON laid out over several lines, and anything else exactly as it came back.
     *
     * A tool's arguments and its result are one long line as they travel, which in a pane this
     * narrow is a paragraph of punctuation.
     */
    private fun readable(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        return runCatching {
            com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(com.google.gson.JsonParser.parseString(trimmed))
        }.getOrDefault(trimmed)
    }

    private fun clearHistory() {
        service.clearHistory()
        activityTable.clear()
        detailArea.text = EMPTY_DETAIL
        updateCopyButtons()
    }

    private fun copy(value: String?) {
        if (value.isNullOrEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(value))
    }

    private fun openSettings() =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SpockAdbConfigurable::class.java)

    override fun dispose() {
        disposed = true
        service.removeCallListener(callListener)
    }

    // ------------------------------------------------------------------ rendering

    private companion object {
        const val GAP = 4
        const val SEARCH_COLUMNS = 14
        const val ANY_TOOL = "All tools"

        /** Long enough to read the line, short enough that it is gone before it becomes furniture. */
        const val FEEDBACK_MS = 6000

        // Favour the list: the detail pane is empty until something is selected.
        const val SPLIT_PROPORTION = 0.72f

        /**
         * What a split needs: a list worth scrolling, and a detail pane worth reading.
         *
         * This was one number, 500, chosen when the panel was a tool window tab of its own and
         * had the whole window's height. It now sits under the shell's header and tab row with
         * the status line below, some ninety pixels it no longer has — so an ordinary tool
         * window fell under the threshold, the details collapsed to a title bar, and expanding
         * them re-ran the same check and put them back. Stating the two minimums the split
         * actually needs says what the number is for, and does not have to be re-tuned the
         * next time something is added above the panel.
         */
        const val MIN_LIST_HEIGHT = 260
        const val MIN_DETAIL_HEIGHT = 150

        const val UNIDENTIFIED_HINT = "A client is only known once it calls initialize: plain HTTP POST is " +
            "stateless, and a stdio session carries no identity before that first message."

        const val EMPTY_DETAIL =
            "Select a request or a tool above to see its details here."

        const val ROW_PAD_V = 1
        const val ROW_PAD_H = 6
        const val TOOL_INDENT = "        "

        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val RUNNING = JBColor(0x1F6F4A, 0x57BA8C)
        val ERROR = JBColor(0xB3261E, 0xF2857C)
        val DESTRUCTIVE = JBColor(0x8A6100, 0xE0A030)
        val ACTION = JBColor(0x2C5D92, 0x6EA8E0)

        /** Marks match the safety vocabulary used in the docs and the tool descriptions. */
        fun ToolSafety.marker(): String = when (this) {
            ToolSafety.READ_ONLY -> "✓"
            ToolSafety.SAFE_ACTION -> "⚡"
            ToolSafety.DESTRUCTIVE -> "⚠"
        }

        fun ToolSafety.heading(): String = when (this) {
            ToolSafety.READ_ONLY -> "Read-only — run automatically"
            ToolSafety.SAFE_ACTION -> "Actions — run automatically"
            ToolSafety.DESTRUCTIVE -> "Destructive — always ask first"
        }

        fun ToolSafety.colour(): JBColor = when (this) {
            ToolSafety.READ_ONLY -> RUNNING
            ToolSafety.SAFE_ACTION -> ACTION
            ToolSafety.DESTRUCTIVE -> DESTRUCTIVE
        }

        fun ToolSafety.describe(): String = when (this) {
            ToolSafety.READ_ONLY -> "✓ read-only"
            ToolSafety.SAFE_ACTION -> "⚡ action"
            ToolSafety.DESTRUCTIVE -> "⚠ destructive — requires confirmation"
        }
    }
}
