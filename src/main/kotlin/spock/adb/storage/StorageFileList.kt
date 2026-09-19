package spock.adb.storage

import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The storage files of one app: a search field over the list of them.
 *
 * Its own component so the search, the filtering and the "this selection was made by code, not
 * by the developer" guard live beside each other rather than as four more members of
 * [AppStoragePanel], which is already at the size Detekt complains about.
 */
internal class StorageFileList : JPanel(BorderLayout()) {

    private val files = CollectionListModel<StorageFile>()
    private val list = JBList(files)
    private val filter = StoragePanelUi.searchField(
        "Search files…",
        "Show only the files whose path contains this text",
    )

    /** Every file the device listed; the list shows the ones [filter] lets through. */
    private var all: List<StorageFile> = emptyList()

    /** True while the selection is being changed by code rather than by the developer. */
    private var ignoreSelection = false

    /** Called on the EDT when the developer selects a file. */
    var onSelected: (StorageFile) -> Unit = {}

    val selected: StorageFile? get() = list.selectedValue

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = StoragePanelUi.fileRenderer()
        list.emptyText.text = NOTHING_LISTED
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !ignoreSelection) selected?.let(onSelected)
        }
        filter.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = showMatches(keep = selected)
            },
        )

        add(filter, BorderLayout.NORTH)
        add(
            JBScrollPane(list).apply {
                minimumSize = Dimension(JBUI.scale(StoragePanelUi.FILE_LIST_WIDTH), 0)
            },
            BorderLayout.CENTER,
        )
    }

    /** Shows [listed], keeping [open] selected when the search still lets it through. */
    fun show(listed: List<StorageFile>, open: StorageFile? = null) {
        all = listed
        showMatches(keep = open)
    }

    fun clear() = show(emptyList())

    /** Selects [file] without calling [onSelected] — for putting back a selection just refused. */
    fun revertTo(file: StorageFile?) = withoutEvents {
        if (file == null) list.clearSelection() else list.setSelectedValue(file, true)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        list.isEnabled = enabled
        filter.isEnabled = enabled
    }

    private fun showMatches(keep: StorageFile?) {
        val matches = matchingStorageFiles(all, filter.text)
        withoutEvents {
            files.replaceAll(matches)
            // A search that closed the file being edited would be a search with a side effect.
            if (keep != null && keep in matches) list.setSelectedValue(keep, true)
        }
        list.emptyText.text = if (all.isEmpty()) NOTHING_LISTED else "No file matches the search"
    }

    private inline fun withoutEvents(block: () -> Unit) {
        ignoreSelection = true
        try {
            block()
        } finally {
            ignoreSelection = false
        }
    }

    private companion object {
        const val NOTHING_LISTED = "No files listed"
    }
}

/**
 * The files whose path contains [query], case-insensitively; all of them when nothing was typed.
 *
 * Matches the whole path, not the name: `shared_prefs` and `datastore` are how a developer asks
 * for one kind of file, and an app routinely holds the same name in both.
 */
internal fun matchingStorageFiles(all: List<StorageFile>, query: String): List<StorageFile> {
    val trimmed = query.trim()
    return if (trimmed.isEmpty()) all else all.filter { it.path.contains(trimmed, ignoreCase = true) }
}
