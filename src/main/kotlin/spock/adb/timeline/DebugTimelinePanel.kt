package spock.adb.timeline

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * The Timeline tab: every event [DebugTimelineService] recorded, oldest at the top.
 *
 * Built to answer one question — what happened just before the bug — so it follows the newest
 * event while the developer is at the bottom, and stops following the moment they scroll up or
 * select something to read. Selecting two events and pressing Copy takes everything between them,
 * not just the two rows: the report needs what happened in between.
 *
 * @param openContext brings forward the tab an event came from, by its title, when it has one.
 */
class DebugTimelinePanel(
    private val project: Project,
    private val openContext: (String) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {

    private val service = DebugTimelineService.getInstance(project)
    private val model = EventModel()
    private val table = JBTable(model)

    private val recordBox = JCheckBox("Record device events", service.recordingDevice).apply {
        toolTipText = "Read the selected app's lifecycle, crashes and warnings from the device's log. " +
            "Actions and agent calls are recorded either way."
    }
    private val categoryCombo = JComboBox<Any>(
        DefaultComboBoxModel<Any>((listOf<Any>(ALL_CATEGORIES) + TimelineCategory.entries).toTypedArray()),
    ).apply { renderer = labelRenderer() }
    private val severityCombo = JComboBox(DefaultComboBoxModel(TimelineSeverity.entries.toTypedArray())).apply {
        renderer = labelRenderer(suffix = " and up")
    }
    private val search = SearchTextField(false)
    private val markerButton = JButton("Add Marker").apply {
        toolTipText = "Note the moment you saw the problem"
    }
    private val copyButton = JButton("Copy All")
    private val exportButton = JButton("Export…")
    private val clearButton = JButton("Clear")
    private val contextButton = JButton().apply { isVisible = false }
    private val statusLabel = JBLabel(" ").apply {
        setComponentStyle(UIUtil.ComponentStyle.SMALL)
        foreground = UIUtil.getContextHelpForeground()
        putClientProperty(HTML_DISABLE, true)
    }
    private val detail = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        border = JBUI.Borders.empty(GAP)
    }

    private val refreshQueued = AtomicBoolean(false)
    private var shownIds: List<Long> = emptyList()
    private val listener: () -> Unit = ::queueRefresh

    @Volatile
    private var disposed = false

    init {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        table.setShowGrid(false)
        table.tableHeader.reorderingAllowed = false
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.emptyText.text = "Nothing recorded yet. Select a device and an app, then use the app."
        table.setDefaultRenderer(Any::class.java, EventRenderer())
        listOf(TIME_WIDTH, SEVERITY_WIDTH, CATEGORY_WIDTH).forEachIndexed { column, width ->
            table.columnModel.getColumn(column).apply {
                preferredWidth = JBUI.scale(width)
                maxWidth = JBUI.scale(width * 2)
            }
        }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelection() }

        add(toolbar(), BorderLayout.NORTH)
        add(
            JBSplitter(true, SPLIT).apply {
                firstComponent = JBScrollPane(table)
                secondComponent = JPanel(BorderLayout()).apply {
                    add(JBScrollPane(detail), BorderLayout.CENTER)
                    add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(contextButton) }, BorderLayout.SOUTH)
                }
            },
            BorderLayout.CENTER,
        )
        wire()
        service.timeline.addListener(listener)
        refresh()
    }

    override fun dispose() {
        disposed = true
        service.timeline.removeListener(listener)
    }

    private fun toolbar(): JPanel = JPanel(BorderLayout()).apply {
        add(
            JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
                add(recordBox)
                add(categoryCombo)
                add(severityCombo)
                add(search)
                add(markerButton)
                add(copyButton)
                add(exportButton)
                add(clearButton)
            },
            BorderLayout.CENTER,
        )
        add(statusLabel.apply { border = JBUI.Borders.empty(0, GAP, 2, GAP) }, BorderLayout.SOUTH)
    }

    private fun wire() {
        recordBox.addActionListener {
            service.recordingDevice = recordBox.isSelected
            refresh()
        }
        categoryCombo.addActionListener { refresh() }
        severityCombo.addActionListener { refresh() }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        markerButton.addActionListener {
            val note = Messages.showInputDialog(project, "What did you just see?", "Add Marker", null)
            if (note != null) service.addMarker(note.trim())
        }
        copyButton.addActionListener { copyRange() }
        exportButton.addActionListener { export() }
        clearButton.addActionListener { service.timeline.clear() }
        contextButton.addActionListener {
            selectedEvents().lastOrNull()?.let { CONTEXT_TABS[it.category] }?.let(openContext)
        }
    }

    /** Coalesces a burst of recorded events into one repaint. */
    private fun queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            refreshQueued.set(false)
            refresh()
        }) { disposed || project.isDisposed }
    }

    private fun filter(): TimelineFilter = TimelineFilter(
        categories = (categoryCombo.selectedItem as? TimelineCategory)?.let { setOf(it) }
            ?: TimelineCategory.entries.toSet(),
        minSeverity = severityCombo.selectedItem as? TimelineSeverity ?: TimelineSeverity.INFO,
        query = search.text.trim(),
    )

    private fun refresh() {
        val following = isFollowing()
        val keep = selectedEvents().map { it.id }.toSet()
        model.events = service.timeline.query(filter())
        // Restoring the selection row by row fired a selection event per row, each re-rendering the
        // detail: quadratic in the selection, on the EDT, on every event that arrived. One batch.
        val selection = table.selectionModel
        selection.valueIsAdjusting = true
        model.fireTableDataChanged()
        keptRanges(keep).forEach { range -> selection.addSelectionInterval(range.first, range.last) }
        selection.valueIsAdjusting = false
        if (following && model.events.isNotEmpty()) {
            table.scrollRectToVisible(table.getCellRect(model.events.lastIndex, 0, true))
        }
        updateStatus()
    }

    /** The rows now holding [ids], as runs of consecutive indices. */
    private fun keptRanges(ids: Set<Long>): List<IntRange> {
        if (ids.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = -1
        model.events.forEachIndexed { index, event ->
            val kept = event.id in ids
            if (kept && start < 0) start = index
            if (!kept && start >= 0) {
                ranges += start until index
                start = -1
            }
        }
        if (start >= 0) ranges += start..model.events.lastIndex
        return ranges
    }

    /** At the bottom with nothing selected: keep showing the newest event as it arrives. */
    private fun isFollowing(): Boolean {
        if (table.selectedRowCount > 0) return false
        val bar = (table.parent?.parent as? JBScrollPane)?.verticalScrollBar ?: return true
        return bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(FOLLOW_SLACK)
    }

    private fun updateStatus() {
        val timeline = service.timeline
        val recording = when {
            service.recordingTarget != null -> "Recording ${service.recordingTarget}"
            service.recordingDevice -> "Not reading a device: select a device and an app."
            else -> "Device recording off."
        }
        val shown = "${model.events.size} of ${timeline.size()} shown"
        val dropped = timeline.dropped.takeIf { it > 0 }?.let { " · oldest $it dropped (keeps ${timeline.capacity})" }
        statusLabel.text = "$recording · $shown${dropped.orEmpty()}"
        copyButton.isEnabled = model.events.isNotEmpty()
        exportButton.isEnabled = model.events.isNotEmpty()
    }

    private fun selectedEvents(): List<TimelineEvent> =
        table.selectedRows.map { table.convertRowIndexToModel(it) }.mapNotNull { model.events.getOrNull(it) }

    private fun showSelection() {
        val selected = selectedEvents()
        // A refresh re-selects the same rows; rewriting the detail then threw away the reader's
        // scroll position in a stack trace every time the app logged.
        val ids = selected.map { it.id }
        if (ids == shownIds) return
        shownIds = ids
        val event = selected.lastOrNull()
        detail.text = when {
            event == null -> ""
            selected.size > 1 -> preview(TimelineExport.range(model.events, selected))
            else -> describe(event)
        }
        detail.caretPosition = 0
        val tab = event?.let { CONTEXT_TABS[it.category] }
        contextButton.isVisible = tab != null && selected.size == 1
        contextButton.text = "Open $tab"
        copyButton.text = if (selected.isEmpty()) "Copy All" else "Copy Range"
    }

    /** A range, capped: the pane is a preview, and Copy Range takes the whole of it. */
    private fun preview(range: List<TimelineEvent>): String {
        if (range.size <= PREVIEW_LIMIT) return TimelineExport.format(range)
        return TimelineExport.format(range.take(PREVIEW_LIMIT)) +
            "… and ${range.size - PREVIEW_LIMIT} more. Copy Range or Export takes them all."
    }

    private fun describe(event: TimelineEvent): String = buildString {
        append(TimelineExport.format(listOf(event)).lineSequence().drop(1).first()).append("\n\n")
        append("Category: ").append(event.category.label).append('\n')
        append("Severity: ").append(event.severity.label).append('\n')
        event.deviceSerial?.let { append("Device:   ").append(it).append('\n') }
        event.deviceTime?.let { append("Device log time: ").append(it).append('\n') }
        if (event.detail.isNotBlank()) append('\n').append(event.detail)
    }

    /** The selected range, or everything shown when nothing is selected. */
    private fun rangeToExport(): List<TimelineEvent> {
        val selected = selectedEvents()
        return if (selected.isEmpty()) model.events else TimelineExport.range(model.events, selected)
    }

    private fun copyRange() {
        val events = rangeToExport()
        CopyPasteManager.getInstance().setContents(StringSelection(TimelineExport.format(events)))
        statusLabel.text = "Copied ${events.size} event(s)."
    }

    // The vararg constructor, as in the Storage and Logcat exports: the others are missing before
    // 2025.1, and the spread keeps Kotlin from picking an overload Plugin Verifier rejects.
    @Suppress("DEPRECATION", "SpreadOperator")
    private fun export() {
        val events = rangeToExport()
        val descriptor = FileSaverDescriptor("Export Timeline", "Save the timeline as text", *arrayOf("txt"))
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as Path?, "spock-timeline.txt") ?: return
        val text = TimelineExport.format(events)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { target.file.writeText(text) }
            ApplicationManager.getApplication().invokeLater({
                statusLabel.text = result.fold(
                    { "Exported ${events.size} event(s) to ${target.file.name}." },
                    { "Export failed: ${it.message}" },
                )
            }) { disposed || project.isDisposed }
        }
    }

    private class EventModel : AbstractTableModel() {
        var events: List<TimelineEvent> = emptyList()

        override fun getRowCount() = events.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val event = events[row]
            return when (column) {
                0 -> TimelineExport.clock(event.timeMs)
                1 -> event.severity.label
                2 -> event.category.label
                else -> event.title
            }
        }
    }

    private inner class EventRenderer : DefaultTableCellRenderer() {
        init {
            putClientProperty(HTML_DISABLE, true)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            val event = model.events.getOrNull(table.convertRowIndexToModel(row))
            if (!isSelected) {
                foreground = when (event?.severity) {
                    TimelineSeverity.ERROR -> ERROR_COLOR
                    TimelineSeverity.WARNING -> WARNING_COLOR
                    else -> table.foreground
                }
            }
            toolTipText = if (column == COLUMNS.lastIndex) event?.title else null
            return this
        }
    }

    private companion object {
        const val GAP = 6
        const val SPLIT = 0.7f
        const val FOLLOW_SLACK = 24
        const val PREVIEW_LIMIT = 300
        const val TIME_WIDTH = 90
        const val SEVERITY_WIDTH = 60
        const val CATEGORY_WIDTH = 75
        const val HTML_DISABLE = "html.disable"
        const val ALL_CATEGORIES = "All categories"

        val COLUMNS = arrayOf("Time", "Severity", "Category", "Event")
        val ERROR_COLOR = JBColor(0xB3261E, 0xF2857C)
        val WARNING_COLOR = JBColor(0x8A5A00, 0xE0B050)

        /** Where each kind of event came from: a Spock ADB tab, Logcat, or the MCP server's activity. */
        val CONTEXT_TABS = mapOf(
            TimelineCategory.LOG to "Logcat",
            TimelineCategory.APP_LIFECYCLE to "Logcat",
            TimelineCategory.MCP to "MCP Server",
            TimelineCategory.STORAGE to "Storage",
            TimelineCategory.BACKGROUND_WORK to "Work",
            TimelineCategory.DEVICE_CONDITION to "Work",
            TimelineCategory.ACTIVITY to "Home",
            TimelineCategory.SPOCK_ACTION to "Home",
        )

        fun labelRenderer(suffix: String = ""): ListCellRenderer<Any?> {
            val base = DefaultListCellRenderer()
            return ListCellRenderer { list, value, index, selected, focused ->
                val text = when (value) {
                    is TimelineCategory -> value.label
                    is TimelineSeverity -> value.label + suffix
                    else -> value?.toString().orEmpty()
                }
                base.getListCellRendererComponent(list, text, index, selected, focused)
            }
        }
    }
}
