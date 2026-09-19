package spock.adb.storage

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import spock.adb.DestructiveActionConfirmation
import spock.adb.LatestRequest
import spock.adb.command.AppStorageChangedException
import spock.adb.command.AppStorageFileRequest
import spock.adb.command.AppStorageShell
import spock.adb.command.AppStorageUnverifiedWriteException
import spock.adb.command.AppStorageWrite
import spock.adb.command.AppStorageWriteRequest
import spock.adb.command.ListAppStorageCommand
import spock.adb.command.ReadAppStorageFileCommand
import spock.adb.command.WriteAppStorageFileCommand
import spock.adb.device.ConnectedDevice
import spock.adb.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * View and edit a debuggable app's SharedPreferences and Preferences DataStore, from the IDE.
 *
 * A section of the Devices tab, so storage sits beside every other action on the selected device,
 * but a component of its own rather than more controls on the viewer, which is already at the size
 * Detekt flags. It reaches the device only through the storage commands, which are the same
 * device functions the MCP tools use, so the panel and an agent cannot differ on what a write
 * does: stop the app, check the file has not changed, write, read back.
 *
 * Text that comes off the device — keys, values, file names — is shown with Swing's HTML
 * rendering switched off. A label whose text starts with `<html>` is rendered as markup, and an
 * app is free to name a preference that way.
 */
class AppStoragePanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true), Disposable {

    /**
     * Called when a change of app was refused because of unapplied edits, with the app this
     * panel is still showing — so whoever chose can put their choice back.
     */
    var onAppKept: (String?) -> Unit = {}

    private val restartAfterWrite = JCheckBox("Restart app after writing")

    private val fileList = StorageFileList()

    private val model = PrefsTableModel()
    private val table = JBTable(model)

    /** Filters the table by key. Sorting is off: the file's own order is the app's order. */
    private val sorter = TableRowSorter(model).apply {
        for (column in 0 until model.columnCount) setSortable(column, false)
    }
    private val keyFilter = StoragePanelUi.searchField(
        "Search keys…",
        "Show only the entries whose key contains this text",
    )

    private val notice = StoragePanelUi.noticeLabel()
    private val statusLabel = StoragePanelUi.statusLabel()
    private val changesLabel = StoragePanelUi.changesLabel()
    private val fileNameLabel = StoragePanelUi.fileNameLabel()
    private val filePathLabel = StoragePanelUi.filePathLabel()

    private val addButton = StoragePanelUi.iconButton(AllIcons.General.Add, "Add an entry")
    private val deleteButton = StoragePanelUi.iconButton(AllIcons.General.Remove, "Delete the selected entries")
    private val reloadButton =
        StoragePanelUi.iconButton(AllIcons.Actions.Refresh, "Read the file again from the device")
    private val applyButton = JButton("Apply changes")

    // "Undo Apply" read as "discard what I typed". It does the opposite: it writes the file
    // back to what the device held before the last apply from this panel.
    private val undoButton = JButton("Revert last apply")
    private val exportButton = JButton("Export…")
    private val importButton = JButton("Import…")

    private var device: ConnectedDevice? = null
    private var session: PrefsEditSession? = null

    /** The package the file list was read from; the field may have been edited since. */
    private var listedPackage: String? = null

    private var lastWrite: UndoPoint? = null
    private var busy = false
    private var disposed = false

    /** True once a write was refused because the file had changed on the device since it was read. */
    private var stale = false

    /** Started and answered on the EDT, so a slow read cannot overwrite the result of a newer one. */
    private val reads = LatestRequest()

    /** What the file held before the last write from this panel, and what that write put there. */
    private class UndoPoint(
        val serial: String,
        val packageName: String,
        val file: StorageFile,
        val previous: ByteArray,
        val written: ByteArray,
    )

    init {
        setAvailableHeight(0)
        StoragePanelUi.prepare(table)
        table.rowSorter = sorter
        table.putClientProperty("terminateEditOnFocusLost", true)
        // Typing in the table jumps to a key, which is how a file with a hundred entries is used.
        TableSpeedSearch.installOn(table)
        ValueRenderer().let { renderer ->
            table.setDefaultRenderer(Any::class.java, renderer)
            table.setDefaultRenderer(String::class.java, renderer)
        }

        setToolbar(header())
        setContent(body())
        wire()
        status(NO_DEVICE)
        updateControls()
    }

    /**
     * Takes the height the tab has left for this panel, and reports whether it changed.
     *
     * The Devices tab is a scrolling column that sizes each section by its preferred height, so a
     * component in it cannot stretch on its own: it is told. Below [MIN_HEIGHT] the tab scrolls
     * instead, which is the honest answer when every other section is open in a short window.
     */
    fun setAvailableHeight(available: Int): Boolean {
        val height = available.coerceAtLeast(JBUI.scale(MIN_HEIGHT))
        if (height == preferredSize?.height) return false
        // Never a width: the column is laid out at the viewport's, and asking for one is what
        // grows a horizontal scrollbar in a docked tool window.
        preferredSize = Dimension(0, height)
        maximumSize = Dimension(Int.MAX_VALUE, height)
        return true
    }

    fun setDevice(connected: ConnectedDevice?) {
        val sameDevice = connected != null && connected.serialNumber == device?.serialNumber
        device = connected
        if (!sameDevice) {
            // Retires reads in flight: their answers are about a device that is no longer selected.
            reads.begin()
            listedPackage = null
            fileList.clear()
            showSession(null)
            status(if (connected == null) NO_DEVICE else CHOOSE_APP)
        }
        updateControls()
    }

    /**
     * Shows the storage of [packageName], which is the app chosen in the tool window's header.
     *
     * The panel had a package picker of its own. With one in the header there would be two on
     * screen disagreeing about which app the tab is showing.
     */
    fun setApp(packageName: String?) {
        val wanted = packageName?.trim()?.ifEmpty { null }
        if (wanted == listedPackage) return
        if (wanted == null) {
            listedPackage = null
            fileList.clear()
            showSession(null)
            return status(CHOOSE_APP)
        }
        listFiles(wanted)
    }

    override fun dispose() {
        disposed = true
        reads.begin()
    }

    // ---------------------------------------------------------------- layout

    private fun header(): JComponent = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
        border = JBUI.Borders.empty(2, GAP)
        add(
            restartAfterWrite.apply {
                toolTipText = "Every write stops the app first. Tick this to launch it again afterwards."
            },
        )
    }

    private fun body(): JComponent {
        applyButton.toolTipText = "Stop the app, write the file, and read it back"
        undoButton.toolTipText = "Write back what the file held before the last apply from this panel"
        exportButton.toolTipText = "Save the file as last read from the device"
        importButton.toolTipText = "Replace the file on the device with one saved earlier"

        // The row actions sit over the table they act on; the file actions sit under it, where
        // Apply is the one that reaches the device and reads as the end of the column.
        val rowActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            add(addButton)
            add(deleteButton)
            add(reloadButton)
        }
        val fileActions = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
            listOf(applyButton, undoButton, exportButton, importButton).forEach { add(it) }
        }

        // Which file is open, and where it is on the device: the name alone is ambiguous once an
        // app keeps a `settings` file under both shared_prefs and datastore.
        val fileHeader = JPanel(BorderLayout()).apply {
            add(fileNameLabel, BorderLayout.NORTH)
            add(filePathLabel, BorderLayout.SOUTH)
        }
        val editorHeader = JPanel(BorderLayout()).apply {
            add(fileHeader, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(keyFilter, BorderLayout.CENTER)
                    add(rowActions, BorderLayout.EAST)
                },
                BorderLayout.CENTER,
            )
            add(notice, BorderLayout.SOUTH)
        }

        val editor = JPanel(BorderLayout()).apply {
            add(editorHeader, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            // Stays put while the table scrolls: what Apply would write, and what last happened.
            add(
                JPanel(BorderLayout()).apply {
                    add(changesLabel, BorderLayout.NORTH)
                    add(fileActions, BorderLayout.CENTER)
                    add(statusLabel, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
        return OnePixelSplitter(false, SPLIT_PROPORTION).apply {
            firstComponent = fileList
            secondComponent = editor
        }
    }

    private fun wire() {
        fileList.onSelected = { fileSelected() }
        table.selectionModel.addListSelectionListener { updateControls() }
        model.onEdited = { updateControls() }
        keyFilter.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    sorter.rowFilter = keyRowFilter(keyFilter.text.trim())
                }
            },
        )
        addButton.addActionListener { addRow() }
        deleteButton.addActionListener { deleteRows() }
        reloadButton.addActionListener { reload() }
        applyButton.addActionListener { apply() }
        undoButton.addActionListener { undo() }
        exportButton.addActionListener { export() }
        importButton.addActionListener { import() }
    }

    // ---------------------------------------------------------------- reading

    private fun listFiles(chosenPackage: String? = null) {
        val target = device ?: return status(NO_DEVICE)
        val packageName = (chosenPackage ?: listedPackage)
            ?.trim()?.ifEmpty { null } ?: return status(CHOOSE_APP)
        if (!confirmDiscard()) {
            // The app was chosen elsewhere, so the refusal has to travel back to whoever chose.
            onAppKept(listedPackage)
            return
        }

        val request = reads.begin()
        status("Listing the storage of $packageName…")
        background({ ListAppStorageCommand().execute(packageName, project, target.device) }) { result ->
            if (!reads.isLatest(request)) return@background
            showSession(null)
            listedPackage = result.map { packageName }.getOrNull()
            fileList.show(result.getOrDefault(emptyList()))
            result
                .onSuccess {
                    status(
                        if (it.isEmpty()) {
                            "$packageName has no SharedPreferences or DataStore files."
                        } else {
                            "${it.size} files in $packageName. Select one to open it."
                        },
                    )
                }
                .onFailure { status(it.message ?: "Could not list the storage of $packageName.") }
        }
    }

    private fun fileSelected() {
        val target = device ?: return status(NO_DEVICE)
        val file = fileList.selected ?: return
        if (file == session?.file) return
        if (!confirmDiscard()) {
            fileList.revertTo(session?.file)
            return
        }
        listedPackage?.let { open(target, file, it) }
    }

    private fun reload() {
        val target = device ?: return status(NO_DEVICE)
        val current = session ?: return
        if (confirmDiscard()) listedPackage?.let { open(target, current.file, it) }
    }

    /**
     * Reads [file] from [target] and shows it, with [message] as the status once it is shown.
     *
     * The device is passed in rather than taken from the field: a caller finishing earlier work
     * has to read from the device that work was about, and decide for itself whether that is
     * still the one shown — see [isStillShowing].
     */
    private fun open(target: ConnectedDevice, file: StorageFile, packageName: String, message: String? = null) {
        val request = reads.begin()
        status(message ?: "Reading ${file.path}…")
        background({
            ReadAppStorageFileCommand().execute(AppStorageFileRequest(packageName, file), project, target.device)
        }) { result ->
            if (!reads.isLatest(request)) return@background
            result
                .onSuccess { bytes ->
                    showSession(PrefsEditSession(file, bytes))
                    status(message ?: "Read ${file.path}, ${bytes.size} bytes.")
                }
                .onFailure {
                    showSession(null)
                    status(listOfNotNull(message, it.message ?: "Could not read ${file.path}.").joinToString(" "))
                }
        }
    }

    private fun showSession(next: PrefsEditSession?) {
        if (table.isEditing) table.cellEditor.cancelCellEditing()
        // Whatever moved on under the last session is not this one's problem: the marker belongs
        // to the rows it was raised over, and those are being replaced.
        stale = false
        session = next
        model.session = next
        table.columnModel.getColumn(TYPE_COLUMN).cellEditor =
            DefaultCellEditor(ComboBox(DefaultComboBoxModel(next?.types.orEmpty().toTypedArray())))
        notice.text = next?.readOnlyReason.orEmpty()
        notice.isVisible = next?.readOnlyReason != null
        // Device-supplied text, in labels with HTML rendering switched off.
        fileNameLabel.text = next?.file?.name ?: NO_FILE
        filePathLabel.text = next?.file?.let { "${it.path}  —  ${it.kind.label}" } ?: " "
        filePathLabel.toolTipText = next?.file?.path
        updateControls()
    }

    // ---------------------------------------------------------------- editing

    private fun addRow() {
        table.stopEditing()
        val current = session ?: return
        // A new row has to be visible to be typed into, and its generated key matches no search.
        if (keyFilter.text.isNotEmpty()) keyFilter.text = ""
        current.addRow()
        model.fireTableDataChanged()
        val row = table.convertRowIndexToView(current.rows.lastIndex)
        if (row >= 0) {
            table.selectionModel.setSelectionInterval(row, row)
            table.scrollRectToVisible(table.getCellRect(row, KEY_COLUMN, true))
            table.editCellAt(row, KEY_COLUMN)
        }
        updateControls()
    }

    private fun deleteRows() {
        table.stopEditing()
        val current = session ?: return
        table.selectedModelRows().sortedDescending()
            .filter { current.rows.getOrNull(it)?.editable == true }
            .forEach { current.rows.removeAt(it) }
        model.fireTableDataChanged()
        updateControls()
    }

    private fun apply() {
        table.stopEditing()
        val current = session ?: return
        val target = device ?: return status(NO_DEVICE)
        val packageName = listedPackage ?: return
        runCatching { current.encode() to current.changes().size }
            .onFailure { status(it.message ?: "Cannot apply.") }
            .onSuccess { (content, count) -> applyChanges(target, packageName, current, content, count) }
    }

    private fun applyChanges(
        target: ConnectedDevice,
        packageName: String,
        current: PrefsEditSession,
        content: ByteArray,
        count: Int,
    ) {
        if (count == 0) return status("Nothing to apply.")
        val changes = if (count == 1) "1 change" else "$count changes"
        val path = current.file.path
        if (confirmWrite(target, packageName, "Apply $changes to $path")) {
            write(target, packageName, current.file, content, current.original, "Applied $changes to $path.")
        }
    }

    private fun undo() {
        table.stopEditing()
        val point = lastWrite ?: return
        val target = device?.takeIf { it.serialNumber == point.serial }
            ?: return status("The last apply was to another device. Select it to undo the apply.")
        if (listedPackage != point.packageName) {
            return status("The last apply was to ${point.packageName}. List its files to undo the apply.")
        }
        val action = "Restore ${point.file.path} to what it held before the last apply"
        if (!confirmDiscard() || !confirmWrite(target, point.packageName, action, undoable = false)) return
        write(
            target,
            point.packageName,
            point.file,
            point.previous,
            expected = point.written,
            done = "Restored ${point.file.path}.",
            undoable = false,
        )
    }

    @Suppress("LongParameterList")
    private fun write(
        target: ConnectedDevice,
        packageName: String,
        file: StorageFile,
        content: ByteArray,
        expected: ByteArray?,
        done: String,
        undoable: Boolean = true,
    ) {
        val restart = restartAfterWrite.isSelected
        val request = AppStorageWriteRequest(packageName, file, content, expected, restart)
        busy = true
        updateControls()
        status("Stopping $packageName and writing ${file.path}…")
        background({ WriteAppStorageFileCommand().execute(request, project, target.device) }) { result ->
            busy = false
            updateControls()
            val write = result.getOrNull()
            // A write that landed but did not read back still replaced the file, so it can be undone.
            val unverified = result.exceptionOrNull() as? AppStorageUnverifiedWriteException
            val previous = write?.previous ?: unverified?.previous
            // What the device holds now: the bytes sent, or — when the write could not be
            // verified — the bytes that came back. Undo writes [previous] over exactly this, so
            // without it there is nothing to check the file against and undo is not offered.
            val onDevice = if (write != null) content else unverified?.written
            if (previous != null) {
                lastWrite = if (undoable && onDevice != null) {
                    UndoPoint(target.serialNumber, packageName, file, previous, onDevice)
                } else {
                    null
                }
            }
            val message = write?.let { "$done ${afterWrite(it, restart, packageName)}${warningOf(it)}" }
                ?: result.exceptionOrNull()?.message
                ?: "Could not write ${file.path}."

            // The file changed on the device since it was read, so nothing was written and the
            // rows on screen are still the developer's unapplied work. Re-reading would throw it
            // away to show a file they did not ask for; the panel says so and waits instead.
            val changed = result.exceptionOrNull() is AppStorageChangedException
            stale = changed
            when {
                changed -> {
                    status(message)
                    updateControls()
                }
                // Shows what the device now holds, not what was sent — unless the tab has moved on.
                isStillShowing(target, packageName) -> open(target, file, packageName, message)
                else -> status(message)
            }
        }
    }

    /** Whether the tab still shows [packageName] on [target], as it did when the work now finishing began. */
    private fun isStillShowing(target: ConnectedDevice, packageName: String): Boolean =
        device?.serialNumber == target.serialNumber && listedPackage == packageName

    // ---------------------------------------------------------------- export and import

    private fun export() {
        val current = session ?: return

        // The vararg constructor, as in the logcat export: the others are missing before 2025.1. With a
        // single extension, Kotlin would otherwise pick the (String, String, String) overload, which
        // Plugin Verifier reports as a NoSuchMethodError on 232 and 242 — the spread forces the vararg one.
        // Kotlin has no other way to reach a vararg overload, and copying one element costs nothing.
        @Suppress("DEPRECATION", "SpreadOperator")
        val descriptor = FileSaverDescriptor(
            "Export ${current.file.name}",
            "Save the file as last read from the device",
            *arrayOf(current.file.name.substringAfterLast('.')),
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target = dialog.save(null as Path?, current.file.name) ?: return
        val bytes = current.original
        background({ target.file.writeBytes(bytes) }) { result ->
            result
                .onSuccess { status("Exported ${current.file.path} to ${target.file.name}.") }
                .onFailure { status("Export failed: ${it.message}") }
        }
    }

    private fun import() {
        table.stopEditing()
        val current = session ?: return
        val target = device ?: return status(NO_DEVICE)
        val packageName = listedPackage ?: return
        val chosen = FileChooser.chooseFile(SINGLE_FILE, project, null) ?: return
        if (confirmDiscard()) importFile(chosen, target, packageName, current)
    }

    private fun importFile(
        chosen: VirtualFile,
        target: ConnectedDevice,
        packageName: String,
        current: PrefsEditSession,
    ) {
        // Reading the file is a background step, and the discard prompt has already been
        // answered: edits made while it runs would be thrown away by the reopen that follows,
        // without ever being offered. The table is closed for the duration instead.
        busy = true
        updateControls()
        background({ contentsOf(chosen) }) { result ->
            busy = false
            updateControls()
            val bytes = result.getOrElse { return@background status(it.message ?: "Could not read ${chosen.name}.") }
            if (!isStillShowing(target, packageName) || session !== current) {
                return@background status(
                    "${chosen.name} was not imported: the device or package changed while it was read.",
                )
            }
            // Refused unless the editor could read it as this kind of file: a write is not a way to
            // put something the app cannot load in place of its preferences.
            PrefsEditSession(current.file, bytes).readOnlyReason?.let {
                return@background status("${chosen.name} was not imported. $it")
            }
            if (confirmWrite(target, packageName, "Replace ${current.file.path} with ${chosen.name}")) {
                write(target, packageName, current.file, bytes, current.original, "Imported ${chosen.name}.")
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun confirmWrite(
        target: ConnectedDevice,
        packageName: String,
        action: String,
        undoable: Boolean = true,
    ): Boolean = DestructiveActionConfirmation.confirmAppStorageWrite(
        project,
        target.info,
        packageName,
        action,
        restartAfterWrite.isSelected,
        undoable,
    )

    private fun confirmDiscard(): Boolean {
        val current = session ?: return true
        if (!current.isDirty) return true
        return MessageDialogBuilder
            .yesNo("Discard Unapplied Changes?", "${current.file.path} has changes that were not applied.")
            .yesText("Discard")
            .noText("Keep Editing")
            .ask(project)
    }

    private fun updateControls() {
        val current = session
        val editable = current != null && current.readOnlyReason == null && !busy
        showChanges(changesLabel, current, stale)
        val rows = current?.rows.orEmpty()
        val selected = table.selectedModelRows().mapNotNull { index -> rows.getOrNull(index) }
        // While a write runs, nothing may change what its callback reopens or what it wrote over.
        fileList.isEnabled = !busy
        model.editable = !busy
        addButton.isEnabled = editable
        deleteButton.isEnabled = editable && selected.any { it.editable }
        // Nothing may be applied over a file that has moved on since it was read: reload first.
        applyButton.isEnabled = editable && current?.isDirty == true && !stale
        reloadButton.isEnabled = current != null && !busy
        undoButton.isEnabled = !busy && lastWrite != null && lastWrite?.serial == device?.serialNumber
        exportButton.isEnabled = current != null && !busy
        importButton.isEnabled = editable
    }

    private fun status(text: String) {
        statusLabel.text = text
    }

    private fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        // Every storage call is a blocking ADB round trip; never on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({ done(result) }) { disposed || project.isDisposed }
        }
    }

    /** Shades rows the editor cannot change and marks values that would not be written. */
    private inner class ValueRenderer : DefaultTableCellRenderer() {
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
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            // `row` is the row on screen; with a search in the key field that is not the row the
            // session holds at that index.
            val entry = session?.rows?.getOrNull(table.convertRowIndexToModel(row))
            val problem = entry?.let { session?.problemWith(it) }
            // A value wider than its column is common — a token, a JSON blob — so the whole of it
            // is one hover away rather than only readable by widening the tab.
            toolTipText = problem
                ?: if (entry?.editable == false) "This entry cannot be edited." else value?.toString()
            if (!isSelected) {
                foreground = when {
                    problem != null && column == VALUE_COLUMN -> JBColor.RED
                    entry?.editable == false -> JBColor.GRAY
                    else -> table.foreground
                }
            }
            return this
        }
    }

    private companion object {
        const val GAP = 4

        /** Below this the tab scrolls rather than squeezing the file list and the table. */
        const val MIN_HEIGHT = 320
        const val SPLIT_PROPORTION = 0.3f
        const val KEY_COLUMN = 0
        const val TYPE_COLUMN = 1
        const val VALUE_COLUMN = 2

        /** Swing's client property that stops a component rendering text that starts with `<html>`. */
        const val HTML_DISABLE = StoragePanelUi.HTML_DISABLE

        const val NO_DEVICE = "No device selected. Choose one at the top of the tool window."
        const val CHOOSE_APP = "Choose a debuggable app at the top of the tool window."
        const val NO_FILE = "No file open"

        val SINGLE_FILE = FileChooserDescriptor(true, false, false, false, false, false)
    }
}

/** Shows the entries whose key contains [query]; null when nothing was typed. */
private fun keyRowFilter(query: String): RowFilter<PrefsTableModel, Int>? =
    if (query.isEmpty()) {
        null
    } else {
        object : RowFilter<PrefsTableModel, Int>() {
            override fun include(entry: Entry<out PrefsTableModel, out Int>): Boolean =
                entry.getStringValue(KEY_COLUMN_INDEX).contains(query, ignoreCase = true)
        }
    }

private fun contentsOf(file: VirtualFile): ByteArray {
    require(file.length <= AppStorageShell.MAX_FILE_BYTES) {
        "${file.name} is larger than ${AppStorageShell.MAX_FILE_BYTES} bytes, which is no preferences file."
    }
    return file.contentsToByteArray()
}

/**
 * How much Apply would write, or why it would refuse.
 *
 * Empty when there is nothing to say. A row that cannot be written yet — an empty type, a value
 * that is not a number — is named here rather than only when Apply is pressed and refuses, and a
 * file that moved on under the editor is said in the one place the change count would otherwise
 * claim everything is fine.
 */
internal fun storageChangeSummary(session: PrefsEditSession?, stale: Boolean): String = when {
    stale -> STALE
    session == null || session.readOnlyReason != null -> ""
    else -> runCatching { session.changes().size }.fold(
        onSuccess = { count ->
            when (count) {
                0 -> ""
                1 -> "1 unsaved change"
                else -> "$count unsaved changes"
            }
        },
        onFailure = { it.message ?: "A row cannot be applied as it stands." },
    )
}

/** Puts [storageChangeSummary] on the line above the button that would write it. */
private fun showChanges(label: JBLabel, session: PrefsEditSession?, stale: Boolean) {
    val text = storageChangeSummary(session, stale)
    label.text = if (text.isEmpty()) " " else "$BULLET $text"
    label.toolTipText = if (stale) STALE_HINT else null
    label.isVisible = text.isNotEmpty()
}

private const val BULLET = "\u25cf"
internal const val STALE = "File changed on device — reload before applying"
private const val STALE_HINT = "Another write — the app itself, an agent, or another tool — replaced this " +
    "file after it was read here. Your edits are still on screen; reading the file again is what " +
    "they have to be made against."

/**
 * The selected rows as indices into the session, not into what the table shows.
 *
 * With a search in the key field the two differ, and deleting by the row on screen would delete
 * whatever the session holds at that position instead.
 */
/** Commits whatever cell is being typed into, so what is read next is what is on screen. */
private fun JBTable.stopEditing() {
    if (isEditing) cellEditor.stopCellEditing()
}

private fun JBTable.selectedModelRows(): List<Int> = selectedRows.map { convertRowIndexToModel(it) }

/** The table's key column, for the row filter, which sees the model rather than the panel. */
private const val KEY_COLUMN_INDEX = 0

/** A staged copy the device would not remove, appended to the status line when there is one. */
private fun warningOf(write: AppStorageWrite): String = write.warning?.let { " $it" }.orEmpty()

/** What happened to the app after a write, for the status line. */
private fun afterWrite(write: AppStorageWrite, restart: Boolean, packageName: String): String = when {
    write.restarted -> "$packageName was stopped and started again."
    restart -> "$packageName was stopped; it has no launchable activity to start again."
    else -> "$packageName was stopped and reads the new values when it next starts."
}

/** Displays a [PrefsEditSession] and writes cell edits straight into its rows. */
private class PrefsTableModel : AbstractTableModel() {

    var session: PrefsEditSession? = null
        set(value) {
            field = value
            fireTableDataChanged()
        }

    var onEdited: () -> Unit = {}

    /** False while a write runs, so no cell can be edited against a file that is being replaced. */
    var editable: Boolean = true

    override fun getRowCount(): Int = session?.rows?.size ?: 0

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getColumnClass(column: Int): Class<*> = if (column == 1) Any::class.java else String::class.java

    override fun getValueAt(row: Int, column: Int): Any? {
        val entry = session?.rows?.getOrNull(row) ?: return null
        return when (column) {
            0 -> entry.key
            1 -> entry.type ?: "—"
            else -> entry.text
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean =
        editable && session?.rows?.getOrNull(row)?.editable == true

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        val entry = session?.rows?.getOrNull(row) ?: return
        when (column) {
            0 -> entry.key = value?.toString().orEmpty()
            1 -> (value as? PrefType)?.let { entry.type = it }
            else -> entry.text = value?.toString().orEmpty()
        }
        fireTableRowsUpdated(row, row)
        onEdited()
    }

    private companion object {
        val COLUMNS = listOf("Key", "Type", "Value")
    }
}
