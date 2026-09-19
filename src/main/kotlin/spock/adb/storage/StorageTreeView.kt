package spock.adb.storage

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The app's data directory as a tree: `shared_prefs`, `databases`, `files`, `cache`, and
 * whatever else it has written.
 *
 * It was a flat list of the preference files alone, which answered "what can I edit" and
 * nothing else — a developer looking for the database their app had just written, or the cache
 * they wanted to confirm was empty, could not see that any of it existed.
 *
 * Listing is not editing. Every file is shown; the ones that cannot be opened in the table are
 * greyed, and writing still goes through [AppStoragePaths.classify], which this does not widen.
 *
 * Directories are read when they are opened rather than all at once: an app's `cache` can hold
 * thousands of files, and reading them to draw a row nobody expanded costs a round trip per
 * directory for nothing.
 */
internal class StorageTreeView : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode(StorageEntry("", isDirectory = true))
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    private val filter = StoragePanelUi.searchField(
        "Search files…",
        "Show only the entries whose name contains this text",
    )

    /** Asks for one directory's contents. Answered on the EDT, with an empty list on failure. */
    var loadChildren: (path: String, done: (List<StorageEntry>) -> Unit) -> Unit = { _, done -> done(emptyList()) }

    /** Called on the EDT when the developer selects a file, or null for a directory or nothing. */
    var onSelected: (StorageEntry?) -> Unit = {}

    /** True while the tree is being rebuilt by code, when a selection is not the developer's. */
    private var rebuilding = false

    val selected: StorageEntry?
        get() = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.entry?.takeIf { !it.isDirectory }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = EntryRenderer()
        tree.emptyText.text = NOTHING_LISTED

        tree.addTreeWillExpandListener(
            object : TreeWillExpandListener {
                override fun treeWillExpand(event: TreeExpansionEvent) = fill(event.path)
                override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
            },
        )
        tree.addTreeSelectionListener { if (!rebuilding) onSelected(selected) }
        filter.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = tree.repaint().also { showMatches() }
            },
        )

        add(filter, BorderLayout.NORTH)
        add(
            JBScrollPane(tree).apply {
                minimumSize = Dimension(JBUI.scale(StoragePanelUi.FILE_LIST_WIDTH), 0)
            },
            BorderLayout.CENTER,
        )
    }

    /** Empties the tree and reads the app's data directory again. */
    fun reload() {
        rebuilding = true
        root.removeAllChildren()
        model.reload()
        rebuilding = false
        tree.emptyText.text = READING
        loadChildren("") { entries ->
            tree.emptyText.text = NOTHING_LISTED
            put(root, entries)
        }
    }

    fun clear() {
        rebuilding = true
        root.removeAllChildren()
        model.reload()
        rebuilding = false
    }

    /**
     * Reads a directory the first time it is opened.
     *
     * A directory that has not been read holds one placeholder child, which is what gives it a
     * handle to click before anything is known about what is inside it.
     */
    private fun fill(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        if (!node.isPlaceholder()) return
        val entry = node.entry ?: return
        loadChildren(entry.path) { children -> put(node, children) }
    }

    private fun put(node: DefaultMutableTreeNode, entries: List<StorageEntry>) {
        rebuilding = true
        node.removeAllChildren()
        entries.forEach { entry ->
            val child = DefaultMutableTreeNode(entry)
            // A directory gets a placeholder so it can be opened; what is really in it is read
            // when somebody opens it.
            if (entry.isDirectory) child.add(DefaultMutableTreeNode(PLACEHOLDER))
            node.add(child)
        }
        model.nodeStructureChanged(node)
        rebuilding = false
        showMatches()
    }

    /**
     * Narrows the tree to the entries whose name matches.
     *
     * The rows are not removed: a tree hides what it does not show by collapsing, and a search
     * that pruned the model would have to rebuild it — and re-read every directory — when the
     * search was cleared. The renderer greys what does not match and the matches are opened.
     */
    private fun showMatches() {
        val query = filter.text.trim()
        if (query.isEmpty()) return
        for (row in 0 until tree.rowCount) {
            val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
            if (node?.entry?.name?.contains(query, ignoreCase = true) == true) {
                tree.expandPath(TreePath(node.path).parentPath)
            }
        }
    }

    private fun matches(entry: StorageEntry): Boolean = matchesStorageSearch(entry.name, filter.text)

    private inner class EntryRenderer : ColoredTreeCellRenderer() {
        @Suppress("LongParameterList")
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val entry = (value as? DefaultMutableTreeNode)?.userObject
            if (entry === PLACEHOLDER) {
                append(READING, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                return
            }
            if (entry !is StorageEntry) return

            icon = iconFor(entry, expanded)
            // Device-supplied text, appended as a fragment so it is never rendered as markup.
            val attributes = when {
                !matches(entry) -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                entry.isDirectory || entry.editable -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                // Listed, and not something the table can open.
                else -> READ_ONLY
            }
            append(entry.name, attributes)
            toolTipText = when {
                entry.isDirectory -> entry.path
                entry.editable -> "${entry.path} — ${entry.file?.kind?.label}"
                else -> "${entry.path} — shown as text; only preference files can be edited"
            }
        }

        private fun iconFor(entry: StorageEntry, expanded: Boolean) = when {
            entry.isDirectory && expanded -> AllIcons.Nodes.Folder
            entry.isDirectory -> AllIcons.Nodes.Folder
            entry.file?.kind == StorageKind.SHARED_PREFERENCES -> AllIcons.FileTypes.Xml
            entry.file?.kind == StorageKind.PREFERENCES_DATASTORE -> AllIcons.FileTypes.Config
            else -> AllIcons.FileTypes.Any_type
        }
    }

    private companion object {
        const val NOTHING_LISTED = "No files listed"
        const val READING = "Reading…"

        /** Stands in for a directory's contents until somebody opens it. */
        val PLACEHOLDER = Any()

        val READ_ONLY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY)

        val DefaultMutableTreeNode.entry: StorageEntry? get() = userObject as? StorageEntry

        fun DefaultMutableTreeNode.isPlaceholder(): Boolean =
            childCount == 1 && (getChildAt(0) as? DefaultMutableTreeNode)?.userObject === PLACEHOLDER
    }
}

/**
 * Whether an entry called [name] answers [query].
 *
 * The name rather than the path: the tree already shows where a file is by where it sits, so
 * matching the path would light up every file under `shared_prefs` for the word "prefs".
 */
internal fun matchesStorageSearch(name: String, query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.isEmpty() || name.contains(trimmed, ignoreCase = true)
}
