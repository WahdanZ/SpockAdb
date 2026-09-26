package spock.adb.uitree

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import spock.adb.command.GetApplicationIDCommand
import spock.adb.device.ConnectedDevice
import spock.adb.device.ops.RecompositionOperations
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The UI Inspector's Recompositions tab: record the app for a few seconds and list how many times
 * each composable composed, most first. Double-click a row to open the composable's source line.
 *
 * A list rather than counts on the tree's rows, because the tree is the accessibility tree and its
 * nodes carry no composable names; pairing a count with a row would be a guess. What records and
 * counts is [RecompositionOperations], shared with `android_get_recomposition_counts`.
 */
internal class RecompositionPanel(
    private val project: Project,
    private val device: () -> ConnectedDevice?,
    /** The package of the window last captured, if any: what "the app on screen" most likely is. */
    private val capturedPackage: () -> String?,
    private val isDisposed: () -> Boolean,
) : JPanel(BorderLayout()) {

    private val packageField = JBTextField(PACKAGE_COLUMNS).apply {
        emptyText.text = "Package: the app on screen"
        toolTipText = "App to record. Empty records the app in the last capture, else the project's app."
    }
    private val durationBox = ComboBox(DURATIONS_SECONDS.map { "$it s" }.toTypedArray()).apply {
        toolTipText = "How long to record. Interact with the app while it records to measure a change."
    }
    private val libraries = JBCheckBox("Include libraries").apply {
        toolTipText = "Also list composables from androidx and Kotlin, such as Text and Box"
        addActionListener { showCounts() }
    }
    private val record = JButton("Record", AllIcons.Actions.Execute).apply { addActionListener { record() } }

    private val model = CountsModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        emptyText.text = NOT_RECORDED
        columnModel.getColumn(0).apply {
            preferredWidth = JBUI.scale(COUNT_COLUMN_WIDTH)
            maxWidth = JBUI.scale(COUNT_COLUMN_WIDTH * 2)
        }
    }
    private val note = InspectorNote().apply {
        border = JBUI.Borders.empty(GAP, GAP * 2)
        text = EXPLANATION
    }

    private var counts: RecompositionCounts? = null
    private var recording = false

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.selectedRow.takeIf { it >= 0 } ?: return false
                openSource(model.rows[table.convertRowIndexToModel(row)])
                return true
            }
        }.installOn(table)

        add(
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
                border = JBUI.Borders.empty(GAP, GAP)
                add(packageField)
                add(durationBox)
                add(libraries)
                add(record)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(note, BorderLayout.SOUTH)
    }

    private fun record() {
        val target = device() ?: return say("Choose a device in the Devices tab first.")
        if (recording) return
        val typed = packageField.text.trim().takeIf { it.isNotEmpty() }
        val fromCapture = capturedPackage()
        val seconds = DURATIONS_SECONDS[durationBox.selectedIndex.coerceAtLeast(0)]
        recording = true
        record.isEnabled = false
        table.emptyText.text = "Recording for $seconds s…"
        say("Recording for $seconds s. Use the app now to measure what an interaction recomposes.")

        // At least the recording's length of blocking ADB: never on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val packageName = typed ?: fromCapture ?: projectApplicationId()
                    ?: error("Type the package to record: none was captured and the project's could not be resolved.")
                packageName to RecompositionOperations(target.device, target.serialNumber)
                    .record(packageName, seconds * MILLIS_PER_SECOND)
            }
            ApplicationManager.getApplication().invokeLater({
                recording = false
                record.isEnabled = true
                result
                    .onSuccess { (packageName, recorded) ->
                        counts = recorded
                        showCounts()
                        say(summary(packageName, recorded, seconds))
                    }
                    .onFailure {
                        table.emptyText.text = NOT_RECORDED
                        say(it.message ?: it.javaClass.simpleName)
                    }
            }) { isDisposed() || project.isDisposed }
        }
    }

    private fun projectApplicationId(): String? =
        runCatching { ReadAction.compute<String?, RuntimeException> { GetApplicationIDCommand.resolve(project) } }
            .getOrNull()

    private fun showCounts() {
        val recorded = counts ?: return
        model.rows = if (libraries.isSelected) recorded.composables else recorded.appOnly()
        table.emptyText.text = when {
            recorded.composables.isEmpty() -> "Nothing composed while recording."
            else -> "Only library composables ran. Tick Include libraries to see them."
        }
    }

    private fun summary(packageName: String, recorded: RecompositionCounts, seconds: Int): String = when {
        recorded.composables.isEmpty() ->
            "$packageName composed nothing in $seconds s. An idle screen should not; interact while recording " +
                "to measure a change."
        else ->
            "$packageName: ${recorded.total} compositions of ${recorded.composables.size} composables in " +
                "$seconds s. A count includes the first composition of anything that appeared. A high count is " +
                "a lead, not proof of a problem. Double-click a row to open its source."
    }

    private fun say(message: String) {
        note.text = message
    }

    /** Finds [count]'s file off the EDT, in the index, and opens it at the line Compose recorded. */
    private fun openSource(count: ComposableCount) {
        if (DumbService.isDumb(project)) return say("Indexing; try again when it finishes.")
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = ReadAction.compute<VirtualFile?, RuntimeException> {
                if (project.isDisposed) return@compute null
                val candidates = FilenameIndex.getVirtualFilesByName(count.file, GlobalSearchScope.allScope(project))
                pickSource(candidates.toList(), count.packageName) { it.path }
            }
            ApplicationManager.getApplication().invokeLater({
                if (file == null) {
                    say("${count.file} is not in the project or its attached sources.")
                } else {
                    OpenFileDescriptor(project, file, (count.line - 1).coerceAtLeast(0), 0).navigate(true)
                }
            }) { isDisposed() || project.isDisposed }
        }
    }

    private class CountsModel : AbstractTableModel() {
        var rows: List<ComposableCount> = emptyList()
            set(value) {
                field = value
                fireTableDataChanged()
            }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) Integer::class.java else String::class.java
        override fun getValueAt(row: Int, column: Int): Any {
            val count = rows[row]
            return when (column) {
                0 -> count.count
                1 -> count.simpleName
                else -> count.location
            }
        }
    }

    private companion object {
        const val GAP = 4
        const val PACKAGE_COLUMNS = 22
        const val COUNT_COLUMN_WIDTH = 60
        const val MILLIS_PER_SECOND = 1_000L
        val DURATIONS_SECONDS = listOf(5, 10, 30)
        val COLUMNS = listOf("Count", "Composable", "Source")
        const val NOT_RECORDED = "Record to count how often each composable runs."
        const val EXPLANATION =
            "Counts come from Compose's composition tracing. The app needs " +
                "androidx.compose.runtime:runtime-tracing and androidx.tracing:tracing-perfetto-binary " +
                "(debug builds are enough); Record says what is missing if it cannot trace."
    }
}

/**
 * Of the files named like a composable's, the one in its package: `Screen.kt` is a common name, and
 * the index returns every one. Falls back to the first when none sits under the package's path.
 */
internal fun <T> pickSource(candidates: List<T>, packageName: String, pathOf: (T) -> String): T? {
    if (candidates.size <= 1 || packageName.isEmpty()) return candidates.firstOrNull()
    val directory = "/" + packageName.replace('.', '/') + "/"
    return candidates.firstOrNull { pathOf(it).contains(directory) } ?: candidates.first()
}
