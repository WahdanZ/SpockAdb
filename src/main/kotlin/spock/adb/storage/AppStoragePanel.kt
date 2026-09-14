package spock.adb.storage

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
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import spock.adb.DestructiveActionConfirmation
import spock.adb.LatestRequest
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
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * View and edit a debuggable app's SharedPreferences and Preferences DataStore, from the IDE.
 *
 * A tab of its own rather than more controls on the Devices panel, which is already at the size
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

    private val packagePicker = AppPackagePicker(project, isAlive = { !disposed })
    private val restartAfterWrite = JCheckBox("Restart app after writing")

    private val files = CollectionListModel<StorageFile>()
    private val fileList = JBList(files)

    private val model = PrefsTableModel()
    private val table = JBTable(model)
    private val notice = plainLabel()
    private val statusLabel = plainLabel()

    private val addButton = JButton("Add")
    private val deleteButton = JButton("Delete")
    private val reloadButton = JButton("Reload")
    private val applyButton = JButton("Apply")
    private val undoButton = JButton("Undo Last Apply")
    private val exportButton = JButton("Export…")
    private val importButton = JButton("Import…")

    private var device: ConnectedDevice? = null
    private var session: PrefsEditSession? = null

    /** The package the file list was read from; the field may have been edited since. */
    private var listedPackage: String? = null
    private var lastWrite: UndoPoint? = null
    private var busy = false
    private var disposed = false

    /** True while the file list's selection is being changed by code rather than by the developer. */
    private var ignoreSelection = false

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
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.cellRenderer = SimpleListCellRenderer.create { label, file, _ ->
            label.putClientProperty(HTML_DISABLE, true)
            label.text = file.path
            label.toolTipText = file.kind.label
        }
        table.setShowGrid(false)
        table.putClientProperty("terminateEditOnFocusLost", true)
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

    fun setDevice(connected: ConnectedDevice?) {
        val sameDevice = connected != null && connected.serialNumber == device?.serialNumber
        device = connected
        if (!sameDevice) {
            // Retires reads in flight: their answers are about a device that is no longer selected.
            reads.begin()
            listedPackage = null
            withoutSelectionEvents { files.removeAll() }
            showSession(null)
            status(if (connected == null) NO_DEVICE else "Enter or choose the package of a debuggable app.")
        }
        packagePicker.load(connected, keepSelection = sameDevice)
        updateControls()
    }

    override fun dispose() {
        disposed = true
        reads.begin()
    }

    // ---------------------------------------------------------------- layout

    private fun header(): JComponent = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2))).apply {
        border = JBUI.Borders.empty(2, GAP)
        add(JBLabel("Package:"))
        add(packagePicker)
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

        val buttons = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(GAP), JBUI.scale(2)))
        listOf(addButton, deleteButton, reloadButton, applyButton, undoButton, exportButton, importButton)
            .forEach { buttons.add(it) }

        val editor = JPanel(BorderLayout()).apply {
            add(notice.apply { border = JBUI.Borders.empty(2, GAP) }, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(buttons, BorderLayout.NORTH)
                    add(statusLabel.apply { border = JBUI.Borders.empty(2, GAP) }, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
        return OnePixelSplitter(false, SPLIT_PROPORTION).apply {
            firstComponent = JBScrollPane(fileList)
            secondComponent = editor
        }
    }

    private fun wire() {
        packagePicker.onChosen = { packageName -> listFiles(packageName) }
        packagePicker.onFailure = { status(it) }
        fileList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !ignoreSelection) fileSelected()
        }
        table.selectionModel.addListSelectionListener { updateControls() }
        model.onEdited = { updateControls() }
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
        val packageName = (chosenPackage ?: packagePicker.selected)
            ?.trim()?.ifEmpty { null } ?: return status("Enter or choose the package of a debuggable app.")
        if (!confirmDiscard()) {
            packagePicker.revertTo(listedPackage)
            return
        }

        val request = reads.begin()
        status("Listing the storage of $packageName…")
        background({ ListAppStorageCommand().execute(packageName, project, target.device) }) { result ->
            if (!reads.isLatest(request)) return@background
            showSession(null)
            listedPackage = result.map { packageName }.getOrNull()
            withoutSelectionEvents { files.replaceAll(result.getOrDefault(emptyList())) }
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
        val file = fileList.selectedValue ?: return
        if (file == session?.file) return
        if (!confirmDiscard()) {
            withoutSelectionEvents { fileList.setSelectedValue(session?.file, true) }
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
        session = next
        model.session = next
        table.columnModel.getColumn(TYPE_COLUMN).cellEditor =
            DefaultCellEditor(ComboBox(DefaultComboBoxModel(next?.types.orEmpty().toTypedArray())))
        notice.text = next?.readOnlyReason ?: " "
        updateControls()
    }

    // ---------------------------------------------------------------- editing

    private fun addRow() {
        stopEditing()
        val current = session ?: return
        current.addRow()
        model.fireTableDataChanged()
        val last = current.rows.lastIndex
        table.selectionModel.setSelectionInterval(last, last)
        table.editCellAt(last, KEY_COLUMN)
        updateControls()
    }

    private fun deleteRows() {
        stopEditing()
        val current = session ?: return
        table.selectedRows.sortedDescending()
            .filter { current.rows.getOrNull(it)?.editable == true }
            .forEach { current.rows.removeAt(it) }
        model.fireTableDataChanged()
        updateControls()
    }

    private fun apply() {
        stopEditing()
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
        stopEditing()
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
            if (previous != null) {
                lastWrite = if (undoable) UndoPoint(target.serialNumber, packageName, file, previous, content) else null
            }
            val message = write?.let { "$done ${afterWrite(it, restart, packageName)}" }
                ?: result.exceptionOrNull()?.message
                ?: "Could not write ${file.path}."
            // Shows what the device now holds, not what was sent — unless the tab has moved on meanwhile.
            if (isStillShowing(target, packageName)) open(target, file, packageName, message) else status(message)
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
        stopEditing()
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
        background({ contentsOf(chosen) }) { result ->
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

    private fun stopEditing() {
        if (table.isEditing) table.cellEditor.stopCellEditing()
    }

    private fun updateControls() {
        val current = session
        val editable = current != null && current.readOnlyReason == null && !busy
        val rows = current?.rows.orEmpty()
        val selected = table.selectedRows.toList().mapNotNull { index -> rows.getOrNull(index) }
        // While a write runs, nothing may change what its callback reopens or what it wrote over.
        packagePicker.isEnabled = !busy && device != null
        fileList.isEnabled = !busy
        model.editable = !busy
        addButton.isEnabled = editable
        deleteButton.isEnabled = editable && selected.any { it.editable }
        applyButton.isEnabled = editable && current?.isDirty == true
        reloadButton.isEnabled = current != null && !busy
        undoButton.isEnabled = !busy && lastWrite != null && lastWrite?.serial == device?.serialNumber
        exportButton.isEnabled = current != null && !busy
        importButton.isEnabled = editable
    }

    private fun status(text: String) {
        statusLabel.text = text
    }

    private inline fun withoutSelectionEvents(block: () -> Unit) {
        ignoreSelection = true
        try {
            block()
        } finally {
            ignoreSelection = false
        }
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
            val entry = session?.rows?.getOrNull(row)
            val problem = entry?.let { session?.problemWith(it) }
            toolTipText = problem ?: if (entry?.editable == false) "This entry cannot be edited." else null
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
        const val SPLIT_PROPORTION = 0.3f
        const val KEY_COLUMN = 0
        const val TYPE_COLUMN = 1
        const val VALUE_COLUMN = 2

        /** Swing's client property that stops a component rendering text that starts with `<html>`. */
        const val HTML_DISABLE = "html.disable"

        const val NO_DEVICE = "No device selected. Choose one in the Devices tab."

        val SINGLE_FILE = FileChooserDescriptor(true, false, false, false, false, false)

        fun plainLabel() = JBLabel(" ").apply { putClientProperty(HTML_DISABLE, true) }
    }
}

private fun contentsOf(file: VirtualFile): ByteArray {
    require(file.length <= AppStorageShell.MAX_FILE_BYTES) {
        "${file.name} is larger than ${AppStorageShell.MAX_FILE_BYTES} bytes, which is no preferences file."
    }
    return file.contentsToByteArray()
}

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
