package spock.adb.storage

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JTable
import javax.swing.ListCellRenderer

/**
 * How the storage panel looks, kept out of [AppStoragePanel] so the panel stays about what it
 * does: read a file, edit rows, write them back.
 */
internal object StoragePanelUi {

    const val KEY_COLUMN_WIDTH = 170
    const val TYPE_COLUMN_WIDTH = 110
    const val FILE_LIST_WIDTH = 240
    private const val ROW_PADDING = 6

    /**
     * The file's own name first, with its directory and kind beside it in grey.
     *
     * The paths are long enough that a plain `path` fills the list and pushes the part that
     * differs out of view, which is the name.
     */
    fun fileRenderer(): ListCellRenderer<StorageFile> = object : ColoredListCellRenderer<StorageFile>() {
        override fun customizeCellRenderer(
            list: JList<out StorageFile>,
            value: StorageFile?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val file = value ?: return
            icon = iconFor(file.kind)
            // Device-supplied text: appended as plain fragments, never as a label's HTML.
            append(file.name)
            append("  ${file.path.substringBeforeLast('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            toolTipText = "${file.path} — ${file.kind.label}"
        }
    }

    private fun iconFor(kind: StorageKind): Icon = when (kind) {
        StorageKind.SHARED_PREFERENCES -> AllIcons.FileTypes.Xml
        StorageKind.PREFERENCES_DATASTORE -> AllIcons.FileTypes.Config
        StorageKind.PROTO_DATASTORE -> AllIcons.General.Warning
    }

    /** Row height, striping and column widths, so a value is not squeezed by the key beside it. */
    fun prepare(table: JBTable) {
        table.setShowGrid(false)
        table.setStriped(true)
        table.rowHeight = table.rowHeight + JBUI.scale(ROW_PADDING)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = "Select a file to open it"
        with(table.columnModel) {
            getColumn(0).preferredWidth = JBUI.scale(KEY_COLUMN_WIDTH)
            getColumn(1).apply {
                preferredWidth = JBUI.scale(TYPE_COLUMN_WIDTH)
                maxWidth = JBUI.scale(TYPE_COLUMN_WIDTH)
            }
        }
    }

    /** An icon button sized like a toolbar button rather than like a labelled one. */
    fun iconButton(icon: Icon, tooltip: String): JButton = JButton(icon).apply {
        toolTipText = tooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        margin = JBUI.emptyInsets()
        val side = JBUI.scale(ICON_BUTTON_SIDE)
        preferredSize = Dimension(side, side)
        maximumSize = preferredSize
    }

    /** The line that says why a file cannot be edited: shown only when there is something to say. */
    fun noticeLabel(): JBLabel = JBLabel(" ").apply {
        putClientProperty(HTML_DISABLE, true)
        icon = AllIcons.General.Warning
        border = JBUI.Borders.empty(2, GAP)
        isVisible = false
    }

    /** The line that says what just happened, in the quieter colour used for hints. */
    fun statusLabel(): JBLabel = JBLabel(" ").apply {
        putClientProperty(HTML_DISABLE, true)
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, GAP)
    }

    const val GAP = 4
    const val ICON_BUTTON_SIDE = 26

    /** Swing's client property that stops a component rendering text that starts with `<html>`. */
    const val HTML_DISABLE = "html.disable"
}
