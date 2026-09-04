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
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import spock.adb.mcp.tools.ToolRegistry
import spock.adb.mcp.tools.ToolSafety
import spock.adb.ui.WrapLayout
import spock.adb.ui.renderWith
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

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
    private val copyConfigButton = JButton("Copy Config")

    private val activityModel = DefaultListModel<McpCall>()
    private val activityList = JBList(activityModel)
    private val detailArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(Font.MONOSPACED, font.size)
        text = EMPTY_DETAIL
    }

    private val toolsModel = DefaultListModel<ToolRow>()
    private val toolsList = JBList(toolsModel)
    private val toolSearchField = JBTextField(SEARCH_COLUMNS)
    private val showDestructiveOnly = JCheckBox("Destructive only")

    private val searchField = JBTextField(SEARCH_COLUMNS)
    private val toolFilter = JComboBox<String>()
    private val outcomeFilter = JComboBox(McpHistoryFilter.Outcome.entries.toTypedArray()).apply {
        renderWith { it.name.lowercase().replaceFirstChar(Char::uppercase) }
    }

    private val callListener: (McpCall) -> Unit = { refreshActivityLater() }

    init {
        setToolbar(header())
        setContent(body())

        service.addCallListener(callListener)
        wire()
        refreshStatus()
        refreshActivity()
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
            add(copyConfigButton)
            add(JButton("Settings").apply { addActionListener { openSettings() } })
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
        activityList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        activityList.cellRenderer = ActivityRenderer()
        activityList.setEmptyText("No MCP requests yet.")
        activityList.addListSelectionListener { showDetails() }

        val filters = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, GAP)
            add(JBLabel("Search:"))
            add(searchField)
            add(JBLabel("Tool:"))
            add(toolFilter)
            add(outcomeFilter)
            add(JButton("Clear History").apply { addActionListener { clearHistory() } })
        }

        val activity = JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(JBScrollPane(activityList), BorderLayout.CENTER)
        }

        val details = JPanel(BorderLayout()).apply {
            add(
                JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
                    border = JBUI.Borders.empty(2, GAP)
                    add(JBLabel("Details"))
                    add(JButton("Copy").apply { addActionListener { copy(detailArea.text) } })
                    add(JButton("Copy Request").apply { addActionListener { copy(selected()?.arguments) } })
                    add(JButton("Copy Response").apply { addActionListener { copy(selected()?.result) } })
                },
                BorderLayout.NORTH,
            )
            add(JBScrollPane(detailArea), BorderLayout.CENTER)
        }

        // Activity and Tools share the detail pane below: both answer "what is this call /
        // this tool", so two separate detail views would be redundant in a narrow window.
        val tabs = JBTabbedPane().apply {
            addTab("Activity", activity)
            addTab("Tools (${ToolRegistry.all().size})", toolsPane())
        }

        // A splitter, not a fixed pane: on a narrow docked tool window the developer decides
        // how much room the detail view gets.
        return OnePixelSplitter(true, SPLIT_PROPORTION).apply {
            firstComponent = tabs
            secondComponent = details
        }
    }

    /**
     * The catalogue of what an agent can actually do to the device.
     *
     * The panel previously reported only a count, which told a developer nothing about what
     * they were exposing when they pressed Start. Grouping by safety level is the point:
     * the three destructive tools are the ones worth reading before turning the server on.
     */
    private fun toolsPane(): JComponent {
        toolsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        toolsList.cellRenderer = ToolRenderer()
        toolsList.addListSelectionListener { showToolDetails() }

        val filters = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, GAP)
            add(JBLabel("Search:"))
            add(toolSearchField)
            add(showDestructiveOnly)
        }

        toolSearchField.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyReleased(e: java.awt.event.KeyEvent) = refreshTools()
            },
        )
        showDestructiveOnly.addActionListener { refreshTools() }

        refreshTools()
        return JPanel(BorderLayout()).apply {
            add(filters, BorderLayout.NORTH)
            add(JBScrollPane(toolsList), BorderLayout.CENTER)
        }
    }

    private fun refreshTools() {
        val query = toolSearchField.text.orEmpty()
        val destructiveOnly = showDestructiveOnly.isSelected

        toolsModel.clear()
        // Destructive first: the tools a developer most needs to know about should not be
        // buried at the bottom of an alphabetical list.
        ToolSafety.entries.sortedByDescending { it.ordinal }.forEach { safety ->
            if (destructiveOnly && safety != ToolSafety.DESTRUCTIVE) return@forEach

            val matching = ToolRegistry.bySafety(safety)
                .filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
                .sortedBy { it.name }
            if (matching.isEmpty()) return@forEach

            toolsModel.addElement(ToolRow.Header(safety, matching.size))
            matching.forEach { toolsModel.addElement(ToolRow.Entry(it)) }
        }
    }

    private fun showToolDetails() {
        val tool = (toolsList.selectedValue as? ToolRow.Entry)?.tool ?: return
        detailArea.text = buildString {
            appendLine(tool.name)
            appendLine()
            appendLine("Safety: ${tool.safety.describe()}")
            if (tool.safety == ToolSafety.DESTRUCTIVE) {
                appendLine("         You are asked to approve every call, and denial is the default.")
            }
            appendLine()
            appendLine("Description:")
            appendLine(tool.description)
            appendLine()
            appendLine("Arguments:")
            append(prettyJson(tool.inputSchema))
        }
        detailArea.caretPosition = 0
    }

    private fun prettyJson(json: com.google.gson.JsonObject): String =
        runCatching { com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json) }
            .getOrDefault(json.toString())

    /** A row is either a safety heading or a tool, so one list renders the grouping. */
    private sealed interface ToolRow {
        data class Header(val safety: ToolSafety, val count: Int) : ToolRow
        data class Entry(val tool: spock.adb.mcp.tools.AdbTool) : ToolRow
    }

    private class ToolRenderer : ListCellRenderer<ToolRow> {
        private val label = JBLabel().apply { border = JBUI.Borders.empty(ROW_PAD_V + 1, ROW_PAD_H) }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out ToolRow>,
            value: ToolRow,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            label.isOpaque = true
            label.background = if (isSelected) list.selectionBackground else list.background

            when (value) {
                is ToolRow.Header -> {
                    label.text = "  ${value.safety.marker()}  ${value.safety.heading()} (${value.count})"
                    label.font = list.font.deriveFont(Font.BOLD)
                    label.foreground = if (isSelected) list.selectionForeground else value.safety.colour()
                }
                is ToolRow.Entry -> {
                    label.text = TOOL_INDENT + value.tool.name
                    label.font = JBUI.Fonts.create(Font.MONOSPACED, list.font.size)
                    label.foreground = when {
                        isSelected -> list.selectionForeground
                        value.tool.safety == ToolSafety.DESTRUCTIVE -> DESTRUCTIVE
                        else -> JBColor.foreground()
                    }
                }
            }
            return label
        }
    }

    // ------------------------------------------------------------------ state

    private fun wire() {
        startStopButton.addActionListener { if (service.isRunning) stopServer() else startServer() }
        restartButton.addActionListener {
            stopServer()
            startServer()
        }
        copyConfigButton.addActionListener {
            copy(service.clientConfiguration())
            detailLabel.text = "Configuration copied — it contains an access token for your devices."
        }
        searchField.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyReleased(e: java.awt.event.KeyEvent) = refreshActivity()
            },
        )
        toolFilter.addActionListener { refreshActivity() }
        outcomeFilter.addActionListener { refreshActivity() }
    }

    private fun startServer() {
        service.start()
            .onSuccess { refreshStatus() }
            .onFailure {
                statusLabel.text = "Could not start: ${it.message}"
                statusLabel.foreground = ERROR
            }
    }

    private fun stopServer() {
        service.stop()
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = service.isRunning
        val toolCount = ToolRegistry.all().size

        statusLabel.text = if (running) "● MCP Server running" else "○ MCP Server stopped"
        statusLabel.foreground = if (running) RUNNING else JBColor.GRAY

        detailLabel.text = when {
            // The transport is HTTP on loopback, not stdio. Say what it actually is.
            running -> "·  Transport: HTTP (127.0.0.1:${service.port})  ·  Tools: $toolCount"
            else -> "·  Not accepting connections  ·  Tools available: $toolCount"
        }
        detailLabel.foreground = JBColor.GRAY

        startStopButton.text = if (running) "Stop Server" else "Start MCP Server"
        startStopButton.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        restartButton.isEnabled = running
        copyConfigButton.isEnabled = running

        refreshClientLabel(running)
    }

    /**
     * Reports only what the transport actually tells us.
     *
     * Plain HTTP POST is stateless: there is no connection to be "online" on, and a client
     * that never calls `initialize` reports no identity at all. Presence indicators per
     * client would be invented, so they are not shown.
     */
    private fun refreshClientLabel(running: Boolean) {
        val client = service.connectedClient()
        clientLabel.foreground = JBColor.GRAY
        clientLabel.text = when {
            !running -> " "
            // Wrapped as HTML: a plain JBLabel clips rather than wrapping, and this sentence
            // is longer than a docked tool window is wide.
            client == null ->
                "<html>No client has identified itself yet — HTTP is stateless, so a client " +
                    "is only known once it calls initialize.</html>"
            else -> {
                val version = client.version?.let { " $it" }.orEmpty()
                "Last client: ${client.name}$version   ·   requests: ${service.recentCalls().size}"
            }
        }
    }

    // ------------------------------------------------------------------ activity

    private fun refreshActivityLater() =
        ApplicationManager.getApplication().invokeLater({ refreshActivity() }) { project.isDisposed }

    private fun refreshActivity() {
        val filter = McpHistoryFilter(
            query = searchField.text.orEmpty(),
            tool = (toolFilter.selectedItem as? String)?.takeIf { it != ANY_TOOL },
            outcome = outcomeFilter.selectedItem as McpHistoryFilter.Outcome,
        )

        val previouslySelected = selected()
        activityModel.clear()
        service.queryHistory(filter).forEach(activityModel::addElement)

        refreshToolFilterOptions()
        previouslySelected?.let { restoreSelection(it) }
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

    private fun restoreSelection(call: McpCall) {
        val index = (0 until activityModel.size()).firstOrNull {
            activityModel.get(it).timestamp == call.timestamp && activityModel.get(it).toolName == call.toolName
        } ?: return
        activityList.selectedIndex = index
    }

    private fun selected(): McpCall? = activityList.selectedValue

    private fun showDetails() {
        val call = selected() ?: run {
            detailArea.text = EMPTY_DETAIL
            return
        }
        detailArea.text = buildString {
            appendLine("Tool:         ${call.toolName}")
            appendLine("Safety:       ${call.safety.describe()}")
            appendLine("Status:       ${if (call.isError) "Error" else "Success"}")
            appendLine("Duration:     ${call.durationMs} ms")
            appendLine("Time:         ${TIME_FORMAT.format(Date(call.timestamp))}")
            appendLine("Client:       ${call.client ?: "unidentified"}")
            appendLine("Device:       ${call.deviceSerial ?: "default (selected device)"}")
            if (call.safety == ToolSafety.DESTRUCTIVE) {
                appendLine("Confirmation: ${if (call.wasConfirmed) "approved by you" else "denied or failed"}")
            }
            appendLine()
            appendLine("Arguments:")
            appendLine(call.arguments)
            appendLine()
            appendLine("Result:")
            append(call.result)
        }
        detailArea.caretPosition = 0
    }

    private fun clearHistory() {
        service.clearHistory()
        activityModel.clear()
        detailArea.text = EMPTY_DETAIL
    }

    private fun copy(value: String?) {
        if (value.isNullOrEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(value))
    }

    private fun openSettings() =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SpockAdbConfigurable::class.java)

    override fun dispose() = service.removeCallListener(callListener)

    // ------------------------------------------------------------------ rendering

    private class ActivityRenderer : ListCellRenderer<McpCall> {
        private val label = JBLabel().apply { border = JBUI.Borders.empty(ROW_PAD_V, ROW_PAD_H) }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out McpCall>,
            value: McpCall,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            label.font = JBUI.Fonts.create(Font.MONOSPACED, list.font.size)
            label.isOpaque = true
            label.background = if (isSelected) list.selectionBackground else list.background
            label.text = ACTIVITY_ROW_FORMAT.format(
                TIME_FORMAT.format(Date(value.timestamp)),
                value.safety.marker(),
                value.toolName,
                if (value.isError) "✗" else "✓",
                value.durationMs,
            )
            label.foreground = when {
                isSelected -> list.selectionForeground
                value.isError -> ERROR
                value.safety == ToolSafety.DESTRUCTIVE -> DESTRUCTIVE
                else -> JBColor.foreground()
            }
            return label
        }
    }

    private companion object {
        const val GAP = 4
        const val SEARCH_COLUMNS = 14
        const val ANY_TOOL = "All tools"

        // Favour the list: the detail pane is empty until something is selected.
        const val SPLIT_PROPORTION = 0.72f

        const val EMPTY_DETAIL =
            "Select a request or a tool above to see its details here."

        const val ACTIVITY_ROW_FORMAT = "%s  %s %-32s %s %5d ms"
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
